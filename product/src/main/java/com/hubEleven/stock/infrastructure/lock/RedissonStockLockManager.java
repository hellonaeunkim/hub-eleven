package com.hubEleven.stock.infrastructure.lock;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_LOCK_TIMEOUT;

import com.commonLib.common.exception.GlobalException;
import com.hubEleven.stock.application.port.StockLockManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class RedissonStockLockManager implements StockLockManager {

	private static final int MAX_RETRY_COUNT = 3;
	private static final long WAIT_TIME_SECONDS = 3L;
	private static final long LEASE_TIME_SECONDS = 5L;
	private static final long RETRY_BACKOFF_MILLIS = 100L;

	private final RedissonClient redissonClient;
	private final MeterRegistry meterRegistry;

	private final Timer waitAcquiredTimer;
	private final Timer waitTimeoutTimer;
	private final Timer waitInterruptedTimer;
	private final Timer waitErrorTimer;

	private final Timer holdSuccessTimer;
	private final Timer holdErrorTimer;
	private final Timer holdOwnershipLostTimer;

	public RedissonStockLockManager(RedissonClient redissonClient, MeterRegistry meterRegistry) {
		this.redissonClient = redissonClient;
		this.meterRegistry = meterRegistry;

		this.waitAcquiredTimer = createTimer("stock.lock.wait", "acquired");
		this.waitTimeoutTimer = createTimer("stock.lock.wait", "timeout");
		this.waitInterruptedTimer = createTimer("stock.lock.wait", "interrupted");
		this.waitErrorTimer = createTimer("stock.lock.wait", "error");

		this.holdSuccessTimer = createTimer("stock.lock.hold", "success");
		this.holdErrorTimer = createTimer("stock.lock.hold", "error");
		this.holdOwnershipLostTimer = createTimer("stock.lock.hold", "ownership_lost");
	}

	@Override
	public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
		RLock lock = redissonClient.getLock(lockKey);
		Timer.Sample waitSample = Timer.start(meterRegistry);
		boolean waitRecorded = false;

		try {
			for (int retryCount = 0; retryCount < MAX_RETRY_COUNT; retryCount++) {
				boolean locked = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);

				if (locked) {
					waitSample.stop(waitAcquiredTimer);
					waitRecorded = true;

					return executeWhileHoldingLock(lock, supplier);
				}

				Thread.sleep(RETRY_BACKOFF_MILLIS);
			}

			waitSample.stop(waitTimeoutTimer);
			waitRecorded = true;

			throw new GlobalException(STOCK_LOCK_TIMEOUT);
		} catch (InterruptedException e) {
			waitSample.stop(waitInterruptedTimer);
			waitRecorded = true;

			Thread.currentThread().interrupt();
			throw new GlobalException(STOCK_LOCK_TIMEOUT);
		} catch (RuntimeException e) {
			if (!waitRecorded) {
				waitSample.stop(waitErrorTimer);
			}

			throw e;
		}
	}

	private <T> T executeWhileHoldingLock(RLock lock, Supplier<T> supplier) {
		Timer.Sample holdSample = Timer.start(meterRegistry);
		boolean supplierSucceeded = false;
		boolean unlockSucceeded = false;
		boolean ownershipLost = false;

		try {
			T result = supplier.get();
			supplierSucceeded = true;
			return result;
		} finally {
			try {
				// 다른 요청이 임계 구역에 진입했을 수 있는 락 소유권 상실을 별도로 기록한다.
				if (lock.isHeldByCurrentThread()) {
					lock.unlock();
					unlockSucceeded = true;
				} else {
					ownershipLost = true;
				}
			} finally {
				holdSample.stop(resolveHoldTimer(ownershipLost, supplierSucceeded, unlockSucceeded));
			}
		}
	}

	private Timer resolveHoldTimer(
			boolean ownershipLost, boolean supplierSucceeded, boolean unlockSucceeded) {
		if (ownershipLost) {
			return holdOwnershipLostTimer;
		}

		if (!supplierSucceeded || !unlockSucceeded) {
			return holdErrorTimer;
		}

		return holdSuccessTimer;
	}

	private Timer createTimer(String name, String result) {
		return Timer.builder(name).tag("result", result).register(meterRegistry);
	}
}
