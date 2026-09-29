package com.foodsaver.exception;

public class OfferValidationException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public OfferValidationException(String message) {
		super(message);
	}
}
