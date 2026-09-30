package com.foodsaver.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InvalidIdempotencyKeyException(String message) {
		super(message);
	}
}
