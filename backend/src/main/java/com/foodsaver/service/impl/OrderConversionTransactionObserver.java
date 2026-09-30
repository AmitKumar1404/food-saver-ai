package com.foodsaver.service.impl;

import org.springframework.stereotype.Component;

import com.foodsaver.entity.Inventory;

@Component
class OrderConversionTransactionObserver {

	void beforeInventoryLock(String idempotencyKey) {
	}

	void afterInventoryLock(String idempotencyKey) {
	}

	void afterOrderPersistence(String idempotencyKey) {
	}

	void afterOrderItemPersistence(String idempotencyKey) {
	}

	void afterReservationConversion(
			String idempotencyKey,
			Inventory inventory) {
	}

	void insideReplayTransaction(String idempotencyKey) {
	}
}
