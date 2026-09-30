package com.foodsaver.exception;

public class ReservationAllocationConflictException extends RuntimeException {

	public ReservationAllocationConflictException(String message) {
		super(message);
	}

	public ReservationAllocationConflictException(
			String message,
			Throwable cause) {
		super(message, cause);
	}
}
