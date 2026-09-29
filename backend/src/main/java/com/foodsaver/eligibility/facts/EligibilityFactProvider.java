package com.foodsaver.eligibility.facts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.exception.InventoryNotFoundException;
import com.foodsaver.exception.ProductNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.exception.SurplusDetectionNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;

@Component
public class EligibilityFactProvider {

	private final RestaurantRepository restaurantRepository;
	private final InventoryRepository inventoryRepository;
	private final ProductRepository productRepository;
	private final SurplusDetectionRepository surplusDetectionRepository;

	public EligibilityFactProvider(
			RestaurantRepository restaurantRepository,
			InventoryRepository inventoryRepository,
			ProductRepository productRepository,
			SurplusDetectionRepository surplusDetectionRepository) {
		this.restaurantRepository = restaurantRepository;
		this.inventoryRepository = inventoryRepository;
		this.productRepository = productRepository;
		this.surplusDetectionRepository = surplusDetectionRepository;
	}

	@Transactional(readOnly = true)
	public FoodEligibilityFacts loadFacts(
			UUID restaurantPublicId,
			UUID inventoryPublicId,
			UUID surplusDetectionPublicId) {
		Restaurant restaurant = restaurantRepository.findByPublicId(restaurantPublicId)
				.orElseThrow(() -> new RestaurantNotFoundException(restaurantPublicId));

		Inventory inventory = inventoryRepository
				.findByPublicIdAndRestaurantId(inventoryPublicId, restaurant.getId())
				.orElseThrow(() -> new InventoryNotFoundException(
						"Inventory not found: " + inventoryPublicId
								+ " for restaurant: " + restaurantPublicId));

		validateInventoryOwnership(inventory, restaurant, inventoryPublicId);

		Product product = productRepository
				.findByPublicIdAndRestaurantId(
						inventory.getProduct().getPublicId(),
						restaurant.getId())
				.orElseThrow(() -> new ProductNotFoundException(
						"Product not found: " + inventory.getProduct().getPublicId()
								+ " for restaurant: " + restaurantPublicId));

		validateProductOwnership(product, inventory, restaurant);

		SurplusDetection surplusDetection = surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						surplusDetectionPublicId,
						restaurantPublicId)
				.orElseThrow(() -> new SurplusDetectionNotFoundException(
						"Surplus detection not found: " + surplusDetectionPublicId
								+ " for restaurant: " + restaurantPublicId));

		validateSurplusDetectionOwnership(
				surplusDetection,
				inventory,
				restaurant,
				surplusDetectionPublicId);

		return toFacts(restaurant, product, inventory, surplusDetection, Instant.now());
	}

	private void validateInventoryOwnership(
			Inventory inventory,
			Restaurant restaurant,
			UUID inventoryPublicId) {
		if (!Objects.equals(inventory.getPublicId(), inventoryPublicId)
				|| !Objects.equals(inventory.getRestaurant().getId(), restaurant.getId())) {
			throw new InventoryNotFoundException(
					"Inventory not found: " + inventoryPublicId
							+ " for restaurant: " + restaurant.getPublicId());
		}
	}

	private void validateProductOwnership(
			Product product,
			Inventory inventory,
			Restaurant restaurant) {
		if (!Objects.equals(product.getPublicId(), inventory.getProduct().getPublicId())
				|| !Objects.equals(product.getRestaurant().getId(), restaurant.getId())) {
			throw new ProductNotFoundException(
					"Product not found: " + inventory.getProduct().getPublicId()
							+ " for restaurant: " + restaurant.getPublicId());
		}
	}

	private void validateSurplusDetectionOwnership(
			SurplusDetection surplusDetection,
			Inventory inventory,
			Restaurant restaurant,
			UUID surplusDetectionPublicId) {
		if (!Objects.equals(surplusDetection.getPublicId(), surplusDetectionPublicId)
				|| !Objects.equals(
						surplusDetection.getInventory().getPublicId(),
						inventory.getPublicId())
				|| !Objects.equals(
						surplusDetection.getInventory().getRestaurant().getId(),
						restaurant.getId())) {
			throw new SurplusDetectionNotFoundException(
					"Surplus detection not found: " + surplusDetectionPublicId
							+ " for inventory: " + inventory.getPublicId()
							+ " and restaurant: " + restaurant.getPublicId());
		}
	}

	private FoodEligibilityFacts toFacts(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			SurplusDetection surplusDetection,
			Instant evaluatedAt) {
		return new FoodEligibilityFacts(
				new FoodEligibilityFacts.RestaurantFacts(
						restaurant.getPublicId(),
						restaurant.getBusinessType().name(),
						restaurant.getCountryCode(),
						restaurant.getStatus(),
						restaurant.getTimezone()),
				new FoodEligibilityFacts.ProductFacts(
						product.getPublicId(),
						product.getRestaurant().getPublicId(),
						product.getCategory(),
						product.getStatus()),
				new FoodEligibilityFacts.InventoryFacts(
						inventory.getPublicId(),
						inventory.getRestaurant().getPublicId(),
						product.getPublicId(),
						inventory.getInventoryDate(),
						inventory.getStatus(),
						inventory.getAvailableQuantity(),
						inventory.getVersion()),
				new FoodEligibilityFacts.SurplusDetectionFacts(
						surplusDetection.getPublicId(),
						surplusDetection.getInventory().getPublicId(),
						surplusDetection.getStatus(),
						surplusDetection.getDetectedQuantity(),
						surplusDetection.getThresholdQuantity(),
						surplusDetection.getDetectedAt()),
				evaluatedAt);
	}
}
