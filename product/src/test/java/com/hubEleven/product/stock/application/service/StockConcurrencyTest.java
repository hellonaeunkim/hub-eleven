package com.hubEleven.product.stock.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.hubEleven.product.domain.model.Product;
import com.hubEleven.product.infrastructure.repository.JpaProductRepository;
import com.hubEleven.product.stock.application.fixtures.ProductFixture;
import com.hubEleven.product.stock.application.fixtures.StockFixture;
import com.hubEleven.stock.application.service.StockServiceImpl;
import com.hubEleven.stock.domain.model.Stock;
import com.hubEleven.stock.infrastructure.repository.JpaStockRepository;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
public class StockConcurrencyTest {

	@Autowired private StockServiceImpl stockServiceImpl;

	@Autowired private JpaStockRepository jpaStockRepository;

	@Autowired private JpaProductRepository jpaProductRepository;

	private Product product;

	@BeforeEach
	void setUp() {
		product = ProductFixture.createDefault();
		jpaProductRepository.saveAndFlush(product);

		Stock stock = StockFixture.createFromProductWithQuantity(product, 100);
		jpaStockRepository.saveAndFlush(stock);
	}

	@AfterEach
	void tearDown() {
		jpaStockRepository.deleteAll();
		jpaProductRepository.deleteAll();
	}

	@Test
	@DisplayName("재고 감소 - 100개 동시 요청 시 정확히 차감")
	void decreaseStock_when100ConcurrentRequests_thenSuccess() throws Exception {

		// given
		int threadCount = 100;

		ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

		CountDownLatch readyLatch = new CountDownLatch(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(threadCount);
		Queue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

		// when
		for (int i = 0; i < threadCount; i++) {
			executorService.submit(
					() -> {
						try {
							readyLatch.countDown();
							startLatch.await();
							stockServiceImpl.decreaseStock(StockFixture.decreaseRequest(product, 1));
						} catch (Throwable e) {
							exceptions.add(e);
						} finally {
							doneLatch.countDown();
						}
					});
		}

		readyLatch.await();
		startLatch.countDown();
		doneLatch.await();
		executorService.shutdown();

		// then
		Stock updatedStock =
				jpaStockRepository.findByProductIdAndDeletedAtIsNull(product.getProductId()).orElseThrow();

		assertTrue(exceptions.isEmpty(), exceptionMessages(exceptions));
		assertEquals(0, updatedStock.getQuantity());
	}

	private String exceptionMessages(Queue<Throwable> exceptions) {
		return exceptions.stream()
				.map(throwable -> throwable.getClass().getName() + ": " + throwable.getMessage())
				.collect(Collectors.joining(System.lineSeparator()));
	}
}
