package com.hubEleven.product.stock.application.fixtures;

import com.hubEleven.product.domain.model.Product;
import java.util.UUID;

public class ProductFixture {

	// ===== ID =====

	public static final UUID COMPANY_ID = UUID.randomUUID();

	public static final UUID HUB_ID = UUID.randomUUID();

	// ===== Factory Methods =====

	public static Product createDefault() {
		return Product.create("Default Product", COMPANY_ID, HUB_ID);
	}

	private ProductFixture() {}
}
