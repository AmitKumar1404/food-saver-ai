package com.foodsaver.repository.projection;

import java.math.BigDecimal;
import java.time.Instant;

public record ReservationAllocationTarget(
		Long reservationId,
		Long offerId,
		Instant expiresAt,
		BigDecimal quantity) {
}
