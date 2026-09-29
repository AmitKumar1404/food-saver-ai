package com.foodsaver.exception;

public class SurplusDetectionNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public SurplusDetectionNotFoundException(String message) {
		super(message);
	}
}
