package com.foodsaver.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.response.SurplusDetectionResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.service.SurplusDetectionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping(
		"/api/v1/restaurants/{restaurantPublicId}/inventory/{inventoryPublicId}")
public class SurplusDetectionController {

	private final SurplusDetectionService surplusDetectionService;

	public SurplusDetectionController(SurplusDetectionService surplusDetectionService) {
		this.surplusDetectionService = surplusDetectionService;
	}

	@PostMapping("/surplus-detection")
	@Operation(
			summary = "Detect potential surplus",
			description = "Creates a deterministic surplus detection snapshot by comparing "
					+ "the inventory's available quantity with the configured threshold. "
					+ "POTENTIAL_SURPLUS means the quantity met the threshold; it does not "
					+ "mean the food is safe, eligible, or approved for sale.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Surplus detection snapshot created successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(
									implementation = SurplusDetectionResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "A path parameter is invalid"),
			@ApiResponse(
					responseCode = "404",
					description = "Restaurant or ownership-scoped inventory not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<SurplusDetectionResponse> createDetection(
			@Parameter(description = "Restaurant public UUID", required = true)
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Parameter(description = "Inventory public UUID", required = true)
			@PathVariable("inventoryPublicId") UUID inventoryPublicId) {
		SurplusDetectionResponse response = surplusDetectionService.createDetection(
				restaurantPublicId,
				inventoryPublicId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/surplus-detections")
	@Operation(
			summary = "Get surplus detection history",
			description = "Returns ownership-validated surplus detection snapshots for the "
					+ "inventory, ordered from newest to oldest.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Detection history retrieved successfully",
					content = @Content(
							mediaType = "application/json",
							array = @ArraySchema(
									schema = @Schema(
											implementation =
													SurplusDetectionResponse.class)))),
			@ApiResponse(
					responseCode = "400",
					description = "A path parameter is invalid"),
			@ApiResponse(
					responseCode = "404",
					description = "Restaurant or ownership-scoped inventory not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<List<SurplusDetectionResponse>> getDetectionHistory(
			@Parameter(description = "Restaurant public UUID", required = true)
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Parameter(description = "Inventory public UUID", required = true)
			@PathVariable("inventoryPublicId") UUID inventoryPublicId) {
		List<SurplusDetectionResponse> responses =
				surplusDetectionService.getDetectionHistory(
						restaurantPublicId,
						inventoryPublicId);
		return ResponseEntity.ok(responses);
	}
}
