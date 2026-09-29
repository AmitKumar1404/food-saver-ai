package com.foodsaver.eligibility.facts;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;

public record FoodEligibilityFacts(
		RestaurantFacts restaurant,
		ProductFacts product,
		InventoryFacts inventory,
		SurplusDetectionFacts surplusDetection,
		Instant evaluatedAt) {

	public record RestaurantFacts(
			UUID publicId,
			String businessType,
			String countryCode,
			RestaurantStatus status,
			String timezone) {
	}

	public record ProductFacts(
			UUID publicId,
			UUID restaurantPublicId,
			ProductCategory category,
			ProductStatus status) {
	}

	public record InventoryFacts(
			UUID publicId,
			UUID restaurantPublicId,
			UUID productPublicId,
			LocalDate inventoryDate,
			InventoryStatus status,
			BigDecimal availableQuantity,
			Long version) {
	}

	public record SurplusDetectionFacts(
			UUID publicId,
			UUID inventoryPublicId,
			SurplusDetectionStatus status,
			BigDecimal detectedQuantity,
			BigDecimal thresholdQuantity,
			Instant detectedAt) {
	}
}
