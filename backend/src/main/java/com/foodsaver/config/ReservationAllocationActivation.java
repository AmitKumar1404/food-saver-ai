package com.foodsaver.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import com.foodsaver.exception.ReservationAllocationConflictException;

@Component
public class ReservationAllocationActivation {

	private final AtomicBoolean active = new AtomicBoolean();

	public void activate() {
		active.set(true);
	}

	public boolean isActive() {
		return active.get();
	}

	public void requireActive() {
		if (!active.get()) {
			throw new ReservationAllocationConflictException(
					"Reservation allocation is not enabled");
		}
	}
}
