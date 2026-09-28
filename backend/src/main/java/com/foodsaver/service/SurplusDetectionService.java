package com.foodsaver.service;

import java.util.List;
import java.util.UUID;

import com.foodsaver.dto.response.SurplusDetectionResponse;

public interface SurplusDetectionService {

	SurplusDetectionResponse createDetection(
			UUID restaurantPublicId,
			UUID inventoryPublicId);

	List<SurplusDetectionResponse> getDetectionHistory(
			UUID restaurantPublicId,
			UUID inventoryPublicId);
}
