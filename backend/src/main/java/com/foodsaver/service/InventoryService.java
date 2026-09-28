package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.InventoryCreateRequest;
import com.foodsaver.dto.response.InventoryResponse;

public interface InventoryService {

	InventoryResponse createInventory(
			UUID restaurantPublicId,
			InventoryCreateRequest request);
}
