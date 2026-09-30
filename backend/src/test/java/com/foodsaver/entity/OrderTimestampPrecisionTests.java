package com.foodsaver.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.foodsaver.enums.OrderStatus;

class OrderTimestampPrecisionTests {

	@Test
	void completionUsesOneNormalizedTransactionTimestamp() {
		Order order = new Order(
				null,
				null,
				new BigDecimal("10.00"),
				"INR",
				"order-timestamp",
				"a".repeat(64),
				Instant.parse("2026-09-30T08:00:00.123456789Z"));
		order.initializeSystemFields();

		assertEquals(OrderStatus.CONFIRMED, order.getStatus());
		assertNull(order.getCompletedAt());
		order.complete(Instant.parse("2026-09-30T09:00:00.987654321Z"));
		order.normalizeTimestamps();

		assertEquals(OrderStatus.COMPLETED, order.getStatus());
		assertEquals(
				Instant.parse("2026-09-30T09:00:00.987654Z"),
				order.getCompletedAt());
		assertEquals(order.getCompletedAt(), order.getUpdatedAt());
		assertEquals(0, order.getCompletedAt().getNano() % 1_000);
	}
}
