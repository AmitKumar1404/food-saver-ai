package com.foodsaver.dto.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OfferCreateRequest {

	@NotNull(message = "Eligibility evaluation public ID is required")
	private UUID eligibilityEvaluationPublicId;

	@NotNull(message = "Offered quantity is required")
	@Digits(
			integer = 9,
			fraction = 3,
			message = "Offered quantity must have up to 9 integer and 3 fractional digits")
	@DecimalMin(
			value = "0.001",
			message = "Offered quantity must be greater than zero")
	private BigDecimal offeredQuantity;

	@NotNull(message = "Discount percentage is required")
	@Digits(
			integer = 3,
			fraction = 2,
			message = "Discount percentage must have up to 3 integer and 2 fractional digits")
	private BigDecimal discountPercentage;

	@NotNull(message = "Offer expiry is required")
	private Instant expiresAt;
}
