package com.hubEleven.product.stock.application.fixtures;

import com.hubEleven.product.domain.model.Product;
import com.hubEleven.stock.domain.model.Stock;
import com.hubEleven.stock.presentation.dto.request.StockRequests;

public class StockFixture {

	// ===== Factory Methods =====

	public static Stock createFromProductWithQuantity(Product product, int quantity) {
		return Stock.create(
				product.getProductId(), product.getCompanyId(), product.getHubId(), quantity);
	}

	public static StockRequests.Decrease decreaseRequest(Product product, int quantity) {
		return new StockRequests.Decrease(product.getProductId(), quantity);
	}

	private StockFixture() {}
}
