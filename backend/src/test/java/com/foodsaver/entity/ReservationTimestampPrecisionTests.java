package com.foodsaver.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.foodsaver.enums.ReservationStatus;

class ReservationTimestampPrecisionTests {

	@Test
	void persistenceCallbacksNormalizeFallbackTimestampsToMicroseconds() {
		Reservation reservation = reservation(
				Instant.parse("2026-09-30T08:00:00.123456789Z"));

		reservation.initializeSystemFields();

		assertMicrosecondPrecision(reservation.getCreatedAt());
		assertMicrosecondPrecision(reservation.getUpdatedAt());
		assertMicrosecondPrecision(reservation.getExpiresAt());
		assertEquals(reservation.getCreatedAt(), reservation.getUpdatedAt());

		reservation.setStatus(ReservationStatus.ACTIVE);
		reservation.updateTimestamp();

		assertMicrosecondPrecision(reservation.getUpdatedAt());
	}

	@Test
	void lifecycleTimestampsAndUpdatedAtUseOneNormalizedTransactionTime() {
		Reservation reservation = reservation(
				Instant.parse("2026-09-30T08:00:00.123456789Z"));
		Instant creationTime = Instant.parse("2026-09-30T07:00:00.987654321Z");
		Instant expiryTime = Instant.parse("2026-09-30T08:30:00.111222999Z");
		reservation.initializeCreationTimestamp(creationTime);
		reservation.initializeSystemFields();

		reservation.setStatus(ReservationStatus.EXPIRED);
		reservation.setExpiredAt(expiryTime);
		reservation.updateTimestamp();

		assertEquals(
				Instant.parse("2026-09-30T07:00:00.987654Z"),
				reservation.getCreatedAt());
		assertEquals(
				Instant.parse("2026-09-30T08:00:00.123456Z"),
				reservation.getExpiresAt());
		assertEquals(
				Instant.parse("2026-09-30T08:30:00.111222Z"),
				reservation.getExpiredAt());
		assertEquals(reservation.getExpiredAt(), reservation.getUpdatedAt());

		reservation.setCancelledAt(
				Instant.parse("2026-09-30T09:00:00.222333999Z"));
		reservation.setConvertedAt(
				Instant.parse("2026-09-30T09:30:00.333444999Z"));
		assertMicrosecondPrecision(reservation.getCancelledAt());
		assertMicrosecondPrecision(reservation.getConvertedAt());
	}

	private Reservation reservation(Instant expiresAt) {
		return new Reservation(
				null,
				null,
				null,
				null,
				new BigDecimal("1.000"),
				new BigDecimal("10.00"),
				new BigDecimal("10.00"),
				"INR",
				expiresAt,
				"timestamp-test",
				"a".repeat(64));
	}

	private void assertMicrosecondPrecision(Instant timestamp) {
		assertEquals(0, timestamp.getNano() % 1_000);
	}
}
