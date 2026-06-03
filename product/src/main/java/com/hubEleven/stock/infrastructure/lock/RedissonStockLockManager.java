package com.hubEleven.stock.infrastructure.lock;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_LOCK_TIMEOUT;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import com.commonLib.common.exception.GlobalException;
import com.hubEleven.stock.application.port.StockLockManager;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RedissonStockLockManager implements StockLockManager {

	private static final long WAIT_TIME_SECONDS = 3L;
	private static final long LEASE_TIME_SECONDS = 5L;

	private final RedissonClient redissonClient;

	@Override
	public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
		RLock lock = redissonClient.getLock(lockKey);
		boolean locked = false;

		try {
			locked = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
			if (!locked) {
				throw new GlobalException(STOCK_LOCK_TIMEOUT);
			}

			return supplier.get();
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
