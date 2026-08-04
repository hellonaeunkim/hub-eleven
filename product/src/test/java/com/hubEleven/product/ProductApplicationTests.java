package com.hubEleven.product;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_LOCK_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CONFLICT;

import com.commonLib.common.exception.GlobalException;
import com.commonLib.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ProductApplicationTests {

	@Autowired private GlobalExceptionHandler globalExceptionHandler;

	@Test
	void contextLoads() {}

	@Test
	void globalExceptionHandlerMapsStockLockTimeoutToConflict() {
		assertThat(
				globalExceptionHandler
						.handleBusinessException(new GlobalException(STOCK_LOCK_TIMEOUT))
						.getStatusCode())
				.isEqualTo(CONFLICT);
	}
}
