package com.hubEleven.product.stock.application.service;

import static com.hubEleven.product.domain.exception.ProductErrorCode.PRODUCT_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commonLib.common.exception.GlobalException;
import com.hubEleven.product.domain.model.Product;
import com.hubEleven.product.domain.repository.ProductRepository;
import com.hubEleven.stock.application.dto.StockResult;
import com.hubEleven.stock.application.port.StockLockManager;
import com.hubEleven.stock.application.service.StockDecreaseProcessor;
import com.hubEleven.stock.application.service.StockServiceImpl;
import com.hubEleven.stock.domain.model.Stock;
import com.hubEleven.stock.domain.repository.StockRepository;
import com.hubEleven.stock.presentation.dto.request.StockRequests;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class StockServiceImplUnitTest {

	private final StockRepository stockRepository = mock(StockRepository.class);
	private final ProductRepository productRepository = mock(ProductRepository.class);
	private final StockLockManager stockLockManager = mock(StockLockManager.class);
	private final StockDecreaseProcessor stockDecreaseProcessor = mock(StockDecreaseProcessor.class);

	private StockServiceImpl stockService;

	@BeforeEach
	void setUp() {
		stockService =
				new StockServiceImpl(
						stockRepository, productRepository, stockLockManager, stockDecreaseProcessor);
	}

	@Test
	@DisplayName("재고 차감 - 상품을 조회한 후 락 안에서 재고를 차감한다")
	void decreaseStock_readsProductBeforeLock() {
		UUID productId = UUID.randomUUID();
		String productName = "상품";
		String lockKey = "stock:decrease:" + productId;
		StockRequests.Decrease request = new StockRequests.Decrease(productId, 1);
		Product product = mock(Product.class);
		Stock stock = mock(Stock.class);

		when(productRepository.findByIdNotDeleted(productId)).thenReturn(Optional.of(product));
		when(product.getName()).thenReturn(productName);
		when(stockDecreaseProcessor.decrease(request)).thenReturn(stock);
		when(stockLockManager.executeWithLock(eq(lockKey), any()))
				.thenAnswer(
						invocation -> {
							Supplier<Stock> supplier = invocation.getArgument(1);
							return supplier.get();
						});

		StockResult result = stockService.decreaseStock(request);

		assertThat(result.productName()).isEqualTo(productName);

		InOrder inOrder = inOrder(productRepository, product, stockLockManager, stockDecreaseProcessor);
		inOrder.verify(productRepository).findByIdNotDeleted(productId);
		inOrder.verify(product).getName();
		inOrder.verify(stockLockManager).executeWithLock(eq(lockKey), any());
		inOrder.verify(stockDecreaseProcessor).decrease(request);
	}

	@Test
	@DisplayName("재고 차감 - 상품이 없으면 락을 획득하지 않는다")
	void decreaseStock_whenProductMissing_doesNotAcquireLock() {
		UUID productId = UUID.randomUUID();
		StockRequests.Decrease request = new StockRequests.Decrease(productId, 1);

		when(productRepository.findByIdNotDeleted(productId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> stockService.decreaseStock(request))
				.isInstanceOf(GlobalException.class)
				.satisfies(
						exception ->
								assertThat(((GlobalException) exception).getErrorCode())
										.isEqualTo(PRODUCT_NOT_FOUND));

		verifyNoInteractions(stockLockManager, stockDecreaseProcessor);
	}
}
