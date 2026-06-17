package com.hubEleven.stock.application.port;

import java.util.function.Supplier;

public interface StockLockManager {

	<T> T executeWithLock(String lockKey, Supplier<T> supplier);
}
