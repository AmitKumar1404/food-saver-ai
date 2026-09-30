package com.foodsaver.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.repository.ReservationRepository;

@Service
class ReservationReplayService {

	private final ReservationRepository reservationRepository;
	private final ReservationAllocationTransactionObserver transactionObserver;

	ReservationReplayService(
			ReservationRepository reservationRepository,
			ReservationAllocationTransactionObserver transactionObserver) {
		this.reservationRepository = reservationRepository;
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
		reservationRepository
				.findByCustomerPublicIdAndIdempotencyKey(
						customerPublicId,
						idempotencyKey)
				.ifPresentOrElse(reservation -> {
					if (!requestHash.equals(reservation.getRequestHash())) {
						throw new ReservationIdempotencyConflictException(
								"Idempotency key was already used for a different "
										+ "Reservation request");
					}
				}, () -> {
					throw new ReservationAllocationConflictException(
							"Concurrent Reservation allocation could not be recovered");
				});
	}
}
