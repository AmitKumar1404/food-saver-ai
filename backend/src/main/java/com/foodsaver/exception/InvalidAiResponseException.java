package com.foodsaver.exception;

public class InvalidAiResponseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public InvalidAiResponseException() {
		super("AI model returned an invalid response");
	}
}
