package com.foodsaver.exception;

public class OrderConversionConflictException extends RuntimeException {

	public OrderConversionConflictException(String message) {
		super(message);
	}

	public OrderConversionConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
