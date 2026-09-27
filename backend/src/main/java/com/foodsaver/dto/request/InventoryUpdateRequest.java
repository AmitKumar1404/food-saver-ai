package com.foodsaver.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class InventoryUpdateRequest {

	@NotNull(message = "Inventory date is required")
	private LocalDate inventoryDate;
}
