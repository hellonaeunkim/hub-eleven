package com.hubEleven.stock.application.service;

import static com.hubEleven.stock.domain.exception.StockErrorCode.STOCK_NOT_FOUND;

import com.commonLib.common.exception.GlobalException;
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

	@Transactional
	public Stock decrease(StockRequests.Decrease request) {
		Stock stock =
				stockRepository
						.findByProductIdNotDeleted(request.productId())
						.orElseThrow(() -> new GlobalException(STOCK_NOT_FOUND));

		stock.decreaseQuantity(request.quantity());

		return stock;
	}
}
