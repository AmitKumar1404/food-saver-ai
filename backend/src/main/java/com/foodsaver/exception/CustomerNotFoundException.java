package com.foodsaver.exception;

import java.util.UUID;

public class CustomerNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CustomerNotFoundException(UUID publicId) {
		super("Customer not found with publicId: " + publicId);
	}
}
