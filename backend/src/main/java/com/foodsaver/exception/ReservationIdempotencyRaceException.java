package com.foodsaver.exception;

public class ReservationIdempotencyRaceException extends RuntimeException {

	public ReservationIdempotencyRaceException(Throwable cause) {
		super("Concurrent Reservation idempotency race", cause);
	}
}
