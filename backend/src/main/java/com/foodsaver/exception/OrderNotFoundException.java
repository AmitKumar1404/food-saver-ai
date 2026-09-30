package com.foodsaver.exception;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

	public OrderNotFoundException(UUID publicId) {
		super("Order not found: " + publicId);
	}
}
