package com.hubEleven.product.stock.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubEleven.product.domain.model.Product;
import com.hubEleven.product.infrastructure.repository.JpaProductRepository;
import com.hubEleven.product.stock.application.fixtures.ProductFixture;
import com.hubEleven.product.stock.application.fixtures.StockFixture;
import com.hubEleven.stock.application.dto.StockResult;
import com.hubEleven.stock.application.service.StockServiceImpl;
import com.hubEleven.stock.domain.model.Stock;
import com.hubEleven.stock.infrastructure.repository.JpaStockRepository;
import com.hubEleven.stock.presentation.dto.request.StockRequests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class StockServiceImplTest {

	@Autowired private StockServiceImpl stockServiceImpl;

	@Autowired private JpaStockRepository jpaStockRepository;

	@Autowired private JpaProductRepository jpaProductRepository;

	private Product product;

	// 테스트 전 상품 재고 입력
	@BeforeEach
	public void setUp() {

		product = ProductFixture.createDefault();
		jpaProductRepository.saveAndFlush(product);

		Stock stock = StockFixture.createFromProductWithQuantity(product, 100);
		jpaStockRepository.saveAndFlush(stock);
	}

	@AfterEach
	public void after() {
		jpaStockRepository.deleteAll();
		jpaProductRepository.deleteAll();
	}

	@Test
	@DisplayName("재고 감소 - 단일 요청 성공")
	void decreaseStock_success() {

		// given
		int decreaseAmount = 10;

		StockRequests.Decrease request = StockFixture.decreaseRequest(product, decreaseAmount);

		// when
		StockResult result = stockServiceImpl.decreaseStock(request);

		// then - 반환값 검증
		assertThat(result.productId()).isEqualTo(product.getProductId());
		assertThat(result.companyId()).isEqualTo(product.getCompanyId());
		assertThat(result.hubId()).isEqualTo(product.getHubId());
		assertThat(result.quantity()).isEqualTo(90);
	}
}
