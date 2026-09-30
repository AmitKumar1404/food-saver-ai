package com.foodsaver.repository.projection;

public record OrderCompletionTarget(
		Long orderId,
		Long orderItemId,
		Long customerId,
		Long restaurantId,
		Long productId,
		Long inventoryId,
		Long offerId,
		Long reservationId) {
}
