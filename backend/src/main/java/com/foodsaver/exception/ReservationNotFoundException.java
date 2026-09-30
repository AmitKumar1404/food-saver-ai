package com.foodsaver.exception;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

	public ReservationNotFoundException(UUID publicId) {
		super("Reservation not found: " + publicId);
	}
}
