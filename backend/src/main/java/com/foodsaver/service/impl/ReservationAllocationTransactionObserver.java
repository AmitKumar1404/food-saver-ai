package com.foodsaver.service.impl;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
class ReservationAllocationTransactionObserver {

	void beforeInventoryLock(String idempotencyKey) {
	}

	void afterInventoryLock(String idempotencyKey) {
	}

	void afterOfferLocks(String idempotencyKey, List<Long> offerIds) {
	}

	void afterReservationLocks(
			String idempotencyKey,
			List<Long> reservationIds) {
	}

	void afterExpiryFlush(String idempotencyKey) {
	}

	void afterReservationPersistence(String idempotencyKey) {
	}

	void insideReplayTransaction(String idempotencyKey) {
	}
}
