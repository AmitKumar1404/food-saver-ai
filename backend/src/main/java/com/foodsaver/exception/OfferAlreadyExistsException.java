package com.foodsaver.exception;

public class OfferAlreadyExistsException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public OfferAlreadyExistsException(String message) {
		super(message);
	}
}
