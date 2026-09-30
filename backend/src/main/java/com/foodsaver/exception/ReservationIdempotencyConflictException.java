package com.foodsaver.exception;

public class ReservationIdempotencyConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ReservationIdempotencyConflictException(String message) {
		super(message);
	}
}
