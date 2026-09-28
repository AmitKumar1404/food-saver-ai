package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.InventoryCreateRequest;
import com.foodsaver.dto.response.InventoryResponse;
import com.foodsaver.service.InventoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantPublicId}/inventory")
public class InventoryController {

	private final InventoryService inventoryService;

	public InventoryController(InventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	@PostMapping
	public ResponseEntity<InventoryResponse> createInventory(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Valid @RequestBody InventoryCreateRequest request) {
		InventoryResponse response = inventoryService.createInventory(
				restaurantPublicId,
				request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
