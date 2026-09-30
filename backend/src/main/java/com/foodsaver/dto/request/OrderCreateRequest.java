package com.foodsaver.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderCreateRequest {

	@NotNull(message = "Reservation public ID is required")
	private UUID reservationPublicId;
}
