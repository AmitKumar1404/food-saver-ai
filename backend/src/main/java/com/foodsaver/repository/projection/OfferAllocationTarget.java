package com.foodsaver.repository.projection;

public record OfferAllocationTarget(
		Long offerId,
		Long restaurantId,
		Long productId,
		Long inventoryId) {
}
