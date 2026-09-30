package com.foodsaver.exception;

public class ReservationValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ReservationValidationException(String message) {
		super(message);
	}
}
