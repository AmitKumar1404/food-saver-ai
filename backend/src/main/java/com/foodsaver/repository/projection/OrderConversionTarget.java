package com.foodsaver.repository.projection;

public record OrderConversionTarget(
		Long reservationId,
		Long customerId,
		Long restaurantId,
		Long productId,
		Long inventoryId,
		Long offerId) {
}
