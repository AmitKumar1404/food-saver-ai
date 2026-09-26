package com.foodsaver.exception;

import java.util.UUID;

public class RestaurantNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RestaurantNotFoundException(UUID publicId) {
		super("Restaurant not found with publicId: " + publicId);
	}
}
