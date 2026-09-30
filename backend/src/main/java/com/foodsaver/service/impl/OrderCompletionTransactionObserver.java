package com.foodsaver.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Order;

@Component
class OrderCompletionTransactionObserver {

	void beforeInventoryLock(UUID orderPublicId) {
	}

	void afterInventoryLock(UUID orderPublicId) {
	}

	void afterInventoryUpdate(UUID orderPublicId, Inventory inventory) {
	}

	void afterOrderCompletion(UUID orderPublicId, Order order) {
	}

	void afterFlush(UUID orderPublicId) {
	}

	void beforePostMutationValidation(
			UUID orderPublicId,
			Inventory inventory,
			Order order) {
	}

	void beforeFinalVerification(
			UUID orderPublicId,
			Inventory inventory,
			Order order) {
	}
}
