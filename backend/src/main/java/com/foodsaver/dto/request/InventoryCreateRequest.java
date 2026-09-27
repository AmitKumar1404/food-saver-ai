package com.foodsaver.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class InventoryCreateRequest {

	@NotNull(message = "Product public ID is required")
	private UUID productPublicId;

	@NotNull(message = "Prepared quantity is required")
	@DecimalMin(value = "0.001", message = "Prepared quantity must be greater than zero")
	private BigDecimal preparedQuantity;

	@NotNull(message = "Inventory date is required")
	private LocalDate inventoryDate;
}
