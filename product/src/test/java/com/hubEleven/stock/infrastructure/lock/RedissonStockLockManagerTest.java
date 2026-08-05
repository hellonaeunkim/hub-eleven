package com.hubEleven.stock.infrastructure.lock;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_LOCK_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commonLib.common.exception.GlobalException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonStockLockManagerTest {

	private static final String LOCK_KEY = "stock:decrease:test";

	private final RedissonClient redissonClient = mock(RedissonClient.class);
	private final RLock lock = mock(RLock.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private RedissonStockLockManager lockManager;

	@BeforeEach
	void setUp() {
		when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
		when(lock.isHeldByCurrentThread()).thenReturn(true);

		lockManager = new RedissonStockLockManager(redissonClient, meterRegistry);
	}

	@AfterEach
	void tearDown() {
		Thread.interrupted();
		meterRegistry.close();
	}

	@Test
	@DisplayName("락을 즉시 획득하면 한 번의 시도로 대기 성공과 점유 성공을 기록한다")
	void recordsAcquiredAndSuccess() throws InterruptedException {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

		String result = lockManager.executeWithLock(LOCK_KEY, () -> "done");

		assertThat(result).isEqualTo("done");
		assertThat(timerCount("stock.lock.wait", "acquired")).isEqualTo(1);
		assertThat(timerCount("stock.lock.hold", "success")).isEqualTo(1);

		verify(lock, times(1)).tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS));
		verify(lock).unlock();
	}

	@Test
	@DisplayName("재시도 후 획득하면 두 번 시도하고 대기 시간은 한 번만 기록한다")
	void recordsWaitOnceAfterRetry() throws InterruptedException {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false, true);

		lockManager.executeWithLock(LOCK_KEY, () -> "done");

		assertThat(timerCount("stock.lock.wait", "acquired")).isEqualTo(1);
		assertThat(timerCount("stock.lock.wait", "timeout")).isZero();

		verify(lock, times(2)).tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("재시도를 모두 소진하면 세 번 시도 후 타임아웃만 기록한다")
	void recordsTimeoutWhenNeverAcquired() throws InterruptedException {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

		GlobalException exception =
				assertThrows(
						GlobalException.class, () -> lockManager.executeWithLock(LOCK_KEY, () -> "done"));

		assertThat(exception.getErrorCode()).isEqualTo(STOCK_LOCK_TIMEOUT);
		assertThat(timerCount("stock.lock.wait", "timeout")).isEqualTo(1);
		assertThat(timerCount("stock.lock.wait", "acquired")).isZero();
		assertThat(timerCount("stock.lock.hold", "success")).isZero();
		assertThat(timerCount("stock.lock.hold", "error")).isZero();

		verify(lock, times(3)).tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS));
		verify(lock, never()).unlock();
	}

	@Test
	@DisplayName("대기 중 인터럽트가 발생하면 인터럽트 상태를 복원하고 기록한다")
	void recordsInterrupted() throws InterruptedException {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS)))
				.thenThrow(new InterruptedException());

		GlobalException exception =
				assertThrows(
						GlobalException.class, () -> lockManager.executeWithLock(LOCK_KEY, () -> "done"));

		assertThat(exception.getErrorCode()).isEqualTo(STOCK_LOCK_TIMEOUT);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
		assertThat(timerCount("stock.lock.wait", "interrupted")).isEqualTo(1);

		verify(lock, never()).unlock();
	}

	@Test
	@DisplayName("락 획득 중 예외가 발생하면 대기 오류를 기록하고 원래 예외를 전파한다")
	void recordsWaitErrorWhenTryLockThrows() throws InterruptedException {
		IllegalStateException failure = new IllegalStateException("redis unavailable");
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenThrow(failure);

		assertThatThrownBy(() -> lockManager.executeWithLock(LOCK_KEY, () -> "done")).isSameAs(failure);

		assertThat(timerCount("stock.lock.wait", "error")).isEqualTo(1);
		assertThat(timerCount("stock.lock.wait", "acquired")).isZero();
	}

	@Test
	@DisplayName("락 내부 로직이 실패하면 락을 해제하고 점유 실패를 기록한 뒤 원래 예외를 전파한다")
	void recordsHoldErrorWhenSupplierFails() throws InterruptedException {
		IllegalStateException failure = new IllegalStateException("decrease failed");
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

		assertThatThrownBy(
						() ->
								lockManager.executeWithLock(
										LOCK_KEY,
										() -> {
											throw failure;
										}))
				.isSameAs(failure);

		assertThat(timerCount("stock.lock.hold", "error")).isEqualTo(1);
		assertThat(timerCount("stock.lock.wait", "acquired")).isEqualTo(1);
		assertThat(timerCount("stock.lock.wait", "error")).isZero();

		verify(lock).unlock();
	}

	@Test
	@DisplayName("락 해제에 실패하면 점유 실패를 기록하고 원래 예외를 전파한다")
	void recordsHoldErrorWhenUnlockFails() throws InterruptedException {
		IllegalStateException failure = new IllegalStateException("unlock failed");
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
		doThrow(failure).when(lock).unlock();

		assertThatThrownBy(() -> lockManager.executeWithLock(LOCK_KEY, () -> "done")).isSameAs(failure);

		assertThat(timerCount("stock.lock.hold", "error")).isEqualTo(1);
		assertThat(timerCount("stock.lock.hold", "success")).isZero();
	}

	@Test
	@DisplayName("락 소유권을 잃으면 unlock 없이 소유권 상실을 기록하고 결과는 그대로 반환한다")
	void recordsOwnershipLost() throws InterruptedException {
		when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
		when(lock.isHeldByCurrentThread()).thenReturn(false);

		String result = lockManager.executeWithLock(LOCK_KEY, () -> "done");

		assertThat(result).isEqualTo("done");
		assertThat(timerCount("stock.lock.hold", "ownership_lost")).isEqualTo(1);
		assertThat(timerCount("stock.lock.hold", "success")).isZero();
		assertThat(timerCount("stock.lock.hold", "error")).isZero();

		verify(lock, never()).unlock();
	}

	private long timerCount(String name, String result) {
		return meterRegistry.get(name).tag("result", result).timer().count();
	}
}
