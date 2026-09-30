package com.foodsaver.exception;

public class OrderIdempotencyRaceException extends RuntimeException {

	public OrderIdempotencyRaceException(Throwable cause) {
		super(cause);
	}
}
