package com.foodsaver.repository.projection;

import java.math.BigDecimal;

public record InventoryReservationLedgerTotal(
		Long inventoryId,
		BigDecimal allocatedQuantity) {
}
