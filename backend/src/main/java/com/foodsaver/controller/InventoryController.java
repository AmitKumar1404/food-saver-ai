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
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantPublicId}/inventory")
public class InventoryController {

	private final InventoryService inventoryService;

	public InventoryController(InventoryService inventoryService) {
		this.inventoryService = inventoryService;
	}

	@PostMapping
	@Operation(
			summary = "Create inventory",
			description = "Creates an inventory record for a product owned by the specified "
					+ "restaurant on a given inventory date.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Inventory created successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = InventoryResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Validation failed",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Restaurant or product not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Inventory already exists for the same restaurant, product, "
							+ "and inventory date",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<InventoryResponse> createInventory(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Valid @RequestBody InventoryCreateRequest request) {
		InventoryResponse response = inventoryService.createInventory(
				restaurantPublicId,
				request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
