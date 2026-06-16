package com.hubEleven.stock.infrastructure.lock;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_LOCK_TIMEOUT;

import com.commonLib.common.exception.GlobalException;
import com.hubEleven.stock.application.port.StockLockManager;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedissonStockLockManager implements StockLockManager {

	private static final int MAX_RETRY_COUNT = 3;
	private static final long WAIT_TIME_SECONDS = 3L;
	private static final long LEASE_TIME_SECONDS = 5L;
	private static final long RETRY_BACKOFF_MILLIS = 100L;

	private final RedissonClient redissonClient;

	@Override
	public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
		RLock lock = redissonClient.getLock(lockKey);
		boolean locked = false;

		try {
			for (int retryCount = 0; retryCount < MAX_RETRY_COUNT; retryCount++) {
				locked = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
				if (locked) {
					return supplier.get();
				}

				Thread.sleep(RETRY_BACKOFF_MILLIS);
			}

			throw new GlobalException(STOCK_LOCK_TIMEOUT);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GlobalException(STOCK_LOCK_TIMEOUT);
		} finally {
			if (locked && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
