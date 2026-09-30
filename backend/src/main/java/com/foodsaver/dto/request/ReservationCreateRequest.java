package com.foodsaver.dto.request;

import java.math.BigDecimal;
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
public class ReservationCreateRequest {

	@NotNull(message = "Offer public ID is required")
	private UUID offerPublicId;

	@NotNull(message = "Reservation quantity is required")
	@Digits(
			integer = 9,
			fraction = 3,
			message = "Reservation quantity must have up to 9 integer and 3 fractional digits")
	@DecimalMin(
			value = "0.001",
			message = "Reservation quantity must be greater than zero")
	private BigDecimal quantity;
}
