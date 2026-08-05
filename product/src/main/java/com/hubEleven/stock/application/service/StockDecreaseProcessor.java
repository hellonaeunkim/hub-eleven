package com.hubEleven.stock.application.service;

import static com.hubEleven.product.domain.exception.ProductErrorCode.PRODUCT_NOT_FOUND;
import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_NOT_FOUND;

import com.commonLib.common.exception.GlobalException;
import com.hubEleven.product.domain.model.Product;
import com.hubEleven.product.domain.repository.ProductRepository;
import com.hubEleven.stock.application.dto.StockResult;
import com.hubEleven.stock.domain.model.Stock;
import com.hubEleven.stock.domain.repository.StockRepository;
import com.hubEleven.stock.presentation.dto.request.StockRequests;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class StockDecreaseProcessor {

	private final StockRepository stockRepository;
	private final ProductRepository productRepository;

	@Transactional
	public StockResult decrease(StockRequests.Decrease request) {
		Product product =
				productRepository
						.findByIdNotDeleted(request.productId())
						.orElseThrow(() -> new GlobalException(PRODUCT_NOT_FOUND));

		Stock stock =
				stockRepository
						.findByProductIdNotDeleted(request.productId())
						.orElseThrow(() -> new GlobalException(STOCK_NOT_FOUND));

		stock.decreaseQuantity(request.quantity());

		return StockResult.from(stock, product.getName());
	}
}
