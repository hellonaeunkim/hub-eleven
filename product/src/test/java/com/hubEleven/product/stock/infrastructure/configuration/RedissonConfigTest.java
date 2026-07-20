package com.hubEleven.product.stock.infrastructure.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class RedissonConfigTest {

	@Autowired private RedissonClient redissonClient;

	@Test
	void redissonUsesTestRedisDatabase() {
		assertEquals(1, redissonClient.getConfig().useSingleServer().getDatabase());
	}
}
