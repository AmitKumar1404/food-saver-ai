package com.foodsaver.exception;

public class OrderIdempotencyConflictException extends RuntimeException {

	public OrderIdempotencyConflictException(String message) {
		super(message);
	}
}
