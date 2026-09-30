package com.foodsaver.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderIdempotencyConflictException;
import com.foodsaver.repository.OrderRepository;

@Service
class OrderReplayService {

	private final OrderRepository orderRepository;
	private final OrderConversionTransactionObserver transactionObserver;

	OrderReplayService(
			OrderRepository orderRepository,
			OrderConversionTransactionObserver transactionObserver) {
		this.orderRepository = orderRepository;
		this.transactionObserver = transactionObserver;
	}

	@Transactional(
			readOnly = true,
			isolation = Isolation.READ_COMMITTED,
			propagation = Propagation.REQUIRES_NEW)
	void verifyWinner(
			UUID customerPublicId,
			String idempotencyKey,
			String requestHash) {
		transactionObserver.insideReplayTransaction(idempotencyKey);
		orderRepository.findByCustomerPublicIdAndIdempotencyKey(
				customerPublicId,
				idempotencyKey)
				.ifPresentOrElse(order -> {
					if (!requestHash.equals(order.getRequestHash())) {
						throw new OrderIdempotencyConflictException(
								"Idempotency key was already used for a different "
										+ "Order request");
					}
				}, () -> {
					throw new OrderConversionConflictException(
							"Concurrent Order conversion could not be recovered");
				});
	}
}
