package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.ReservationStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ReservationResponse {

	private UUID publicId;

	private UUID customerPublicId;

	private UUID restaurantPublicId;

	private UUID offerPublicId;

	private UUID inventoryPublicId;

	private BigDecimal quantity;

	private BigDecimal unitPrice;

	private BigDecimal totalAmount;

	private String currencyCode;

	private ReservationStatus status;

	private Instant expiresAt;

	private Instant createdAt;

	private Instant updatedAt;
}
