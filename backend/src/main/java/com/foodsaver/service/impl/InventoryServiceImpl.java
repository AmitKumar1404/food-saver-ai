package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.InventoryCreateRequest;
import com.foodsaver.dto.response.InventoryResponse;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.exception.InventoryAlreadyExistsException;
import com.foodsaver.exception.ProductNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.service.InventoryService;

@Service
public class InventoryServiceImpl implements InventoryService {

	private final InventoryRepository inventoryRepository;
	private final ProductRepository productRepository;
	private final RestaurantRepository restaurantRepository;

	public InventoryServiceImpl(
			InventoryRepository inventoryRepository,
			ProductRepository productRepository,
			RestaurantRepository restaurantRepository) {
		this.inventoryRepository = inventoryRepository;
		this.productRepository = productRepository;
		this.restaurantRepository = restaurantRepository;
	}

	@Override
	@Transactional
	public InventoryResponse createInventory(
			UUID restaurantPublicId,
			InventoryCreateRequest request) {
		Restaurant restaurant = restaurantRepository.findByPublicId(restaurantPublicId)
				.orElseThrow(() -> new RestaurantNotFoundException(restaurantPublicId));

		Product product = productRepository
				.findByPublicIdAndRestaurantId(
						request.getProductPublicId(),
						restaurant.getId())
				.orElseThrow(() -> new ProductNotFoundException(
						"Product not found: " + request.getProductPublicId()
								+ " for restaurant: " + restaurantPublicId));

		boolean inventoryExists =
				inventoryRepository.existsByRestaurantIdAndProductIdAndInventoryDate(
						restaurant.getId(),
						product.getId(),
						request.getInventoryDate());
		if (inventoryExists) {
			throw new InventoryAlreadyExistsException(
					"Inventory already exists for product: " + request.getProductPublicId()
							+ " at restaurant: " + restaurantPublicId
							+ " on date: " + request.getInventoryDate());
		}

		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setPreparedQuantity(request.getPreparedQuantity());
		inventory.setAvailableQuantity(request.getPreparedQuantity());
		inventory.setReservedQuantity(BigDecimal.ZERO);
		inventory.setSoldQuantity(BigDecimal.ZERO);
		inventory.setInventoryDate(request.getInventoryDate());
		inventory.setStatus(InventoryStatus.ACTIVE);

		Inventory savedInventory = inventoryRepository.save(inventory);
		return toResponse(savedInventory);
	}

	private InventoryResponse toResponse(Inventory inventory) {
		InventoryResponse response = new InventoryResponse();
		response.setPublicId(inventory.getPublicId());
		response.setRestaurantPublicId(inventory.getRestaurant().getPublicId());
		response.setProductPublicId(inventory.getProduct().getPublicId());
		response.setPreparedQuantity(inventory.getPreparedQuantity());
		response.setAvailableQuantity(inventory.getAvailableQuantity());
		response.setReservedQuantity(inventory.getReservedQuantity());
		response.setSoldQuantity(inventory.getSoldQuantity());
		response.setInventoryDate(inventory.getInventoryDate());
		response.setStatus(inventory.getStatus());
		response.setCreatedAt(inventory.getCreatedAt());
		response.setUpdatedAt(inventory.getUpdatedAt());
		return response;
	}
}
