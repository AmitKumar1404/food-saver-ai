package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.foodsaver.enums.InventoryStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class InventoryResponse {

	private UUID publicId;

	private UUID restaurantPublicId;

	private UUID productPublicId;

	private BigDecimal preparedQuantity;

	private BigDecimal availableQuantity;

	private BigDecimal reservedQuantity;

	private BigDecimal soldQuantity;

	private LocalDate inventoryDate;

	private InventoryStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
