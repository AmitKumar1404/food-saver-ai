package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;

public interface FoodEligibilityEvaluationService {

	FoodEligibilityEvaluationResponse evaluateAndPersist(
			UUID restaurantPublicId,
			UUID inventoryPublicId,
			UUID surplusDetectionPublicId);
}
