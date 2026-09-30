package com.foodsaver.exception;

import java.util.UUID;

public class OfferNotFoundException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public OfferNotFoundException(UUID publicId) {
		super("Offer not found with publicId: " + publicId);
	}
}
