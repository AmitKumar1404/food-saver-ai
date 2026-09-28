package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.config.SurplusDetectionProperties;
import com.foodsaver.dto.response.SurplusDetectionResponse;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.InventoryNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.SurplusDetectionService;

@Service
public class SurplusDetectionServiceImpl implements SurplusDetectionService {

	private final SurplusDetectionRepository surplusDetectionRepository;
	private final InventoryRepository inventoryRepository;
	private final RestaurantRepository restaurantRepository;
	private final SurplusDetectionProperties surplusDetectionProperties;

	public SurplusDetectionServiceImpl(
			SurplusDetectionRepository surplusDetectionRepository,
			InventoryRepository inventoryRepository,
			RestaurantRepository restaurantRepository,
			SurplusDetectionProperties surplusDetectionProperties) {
		this.surplusDetectionRepository = surplusDetectionRepository;
		this.inventoryRepository = inventoryRepository;
		this.restaurantRepository = restaurantRepository;
		this.surplusDetectionProperties = surplusDetectionProperties;
	}

	@Override
	@Transactional
	public SurplusDetectionResponse createDetection(
			UUID restaurantPublicId,
			UUID inventoryPublicId) {
		Inventory inventory = findOwnedInventory(restaurantPublicId, inventoryPublicId);
		BigDecimal availableQuantity = inventory.getAvailableQuantity();
		BigDecimal thresholdQuantity = surplusDetectionProperties.getThresholdQuantity();

		boolean isPotentialSurplus =
				availableQuantity.compareTo(thresholdQuantity) >= 0;

		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setDetectedQuantity(
				isPotentialSurplus ? availableQuantity : BigDecimal.ZERO);
		detection.setThresholdQuantity(thresholdQuantity);
		detection.setStatus(
				isPotentialSurplus
						? SurplusDetectionStatus.POTENTIAL_SURPLUS
						: SurplusDetectionStatus.NOT_SURPLUS);

		SurplusDetection savedDetection = surplusDetectionRepository.save(detection);
		return toResponse(savedDetection);
	}

	@Override
	@Transactional(readOnly = true)
	public List<SurplusDetectionResponse> getDetectionHistory(
			UUID restaurantPublicId,
			UUID inventoryPublicId) {
		findOwnedInventory(restaurantPublicId, inventoryPublicId);

		return surplusDetectionRepository
				.findByInventoryPublicIdOrderByDetectedAtDesc(inventoryPublicId)
				.stream()
				.map(this::toResponse)
				.toList();
	}

	private Inventory findOwnedInventory(
			UUID restaurantPublicId,
			UUID inventoryPublicId) {
		Restaurant restaurant = restaurantRepository.findByPublicId(restaurantPublicId)
				.orElseThrow(() -> new RestaurantNotFoundException(restaurantPublicId));

		return inventoryRepository
				.findByPublicIdAndRestaurantId(inventoryPublicId, restaurant.getId())
				.orElseThrow(() -> new InventoryNotFoundException(
						"Inventory not found: " + inventoryPublicId
								+ " for restaurant: " + restaurantPublicId));
	}

	private SurplusDetectionResponse toResponse(SurplusDetection detection) {
		SurplusDetectionResponse response = new SurplusDetectionResponse();
		response.setPublicId(detection.getPublicId());
		response.setInventoryPublicId(detection.getInventory().getPublicId());
		response.setDetectedQuantity(detection.getDetectedQuantity());
		response.setThresholdQuantity(detection.getThresholdQuantity());
		response.setStatus(detection.getStatus());
		response.setDetectedAt(detection.getDetectedAt());
		response.setCreatedAt(detection.getCreatedAt());
		response.setUpdatedAt(detection.getUpdatedAt());
		return response;
	}
}
