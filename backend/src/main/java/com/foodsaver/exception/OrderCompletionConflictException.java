package com.foodsaver.exception;

public class OrderCompletionConflictException extends RuntimeException {

	public OrderCompletionConflictException(String message) {
		super(message);
	}

	public OrderCompletionConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
