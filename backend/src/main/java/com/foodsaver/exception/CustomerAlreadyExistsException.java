package com.foodsaver.exception;

public class CustomerAlreadyExistsException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CustomerAlreadyExistsException(String message) {
		super(message);
	}

	public CustomerAlreadyExistsException(String message, Throwable cause) {
		super(message, cause);
	}
}
