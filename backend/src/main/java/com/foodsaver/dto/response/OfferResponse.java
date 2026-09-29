package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.OfferStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OfferResponse {

	private UUID publicId;

	private UUID restaurantPublicId;

	private UUID productPublicId;

	private UUID inventoryPublicId;

	private UUID eligibilityEvaluationPublicId;

	private BigDecimal originalPrice;

	private BigDecimal discountPercentage;

	private BigDecimal offerPrice;

	private String currencyCode;

	private BigDecimal offeredQuantity;

	private Instant startAt;

	private Instant expiresAt;

	private OfferStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
