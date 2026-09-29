package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.service.FoodEligibilityEvaluationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping(
		"/api/v1/restaurants/{restaurantPublicId}/inventory/{inventoryPublicId}"
				+ "/surplus-detections/{surplusDetectionPublicId}"
				+ "/eligibility-evaluations")
public class FoodEligibilityEvaluationController {

	private final FoodEligibilityEvaluationService evaluationService;

	public FoodEligibilityEvaluationController(
			FoodEligibilityEvaluationService evaluationService) {
		this.evaluationService = evaluationService;
	}

	@PostMapping
	@Operation(
			summary = "Evaluate food eligibility",
			description = "Creates an immutable platform eligibility snapshot from "
					+ "ownership-validated Restaurant, Product, Inventory, and Surplus "
					+ "Detection facts. ELIGIBLE_FOR_OFFER is a workflow result and is "
					+ "not food-safety certification. The endpoint does not create an "
					+ "Offer or modify Inventory.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Eligibility evaluation snapshot created successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(
									implementation =
											FoodEligibilityEvaluationResponse.class),
							examples = @ExampleObject(
									value = """
											{
											  "publicId": "550e8400-e29b-41d4-a716-446655440000",
											  "restaurantPublicId": "4d474dd4-cd3f-4c7a-9019-660db82677a1",
											  "inventoryPublicId": "bd8d0cc0-e4e9-4ae9-bc2d-d20ac1058b77",
											  "surplusDetectionPublicId": "e2149394-ad27-40cc-a625-449d4410bb60",
											  "policyKey": "FOOD_ELIGIBILITY_V1",
											  "policyVersion": "1.0",
											  "policySourceReference": "FOODSAVER_INTERNAL_V1_POLICY",
											  "status": "ELIGIBLE_FOR_OFFER",
											  "evaluatedInventoryVersion": 3,
											  "evaluatedAvailableQuantity": 8.000,
											  "evaluatedAt": "2026-09-29T07:00:00Z",
											  "ruleResults": [
											    {
											      "ruleCode": "OWNERSHIP_CONSISTENCY",
											      "outcome": "PASS",
											      "reasonCode": null,
											      "message": "Ownership facts are consistent"
											    }
											  ],
											  "createdAt": "2026-09-29T07:00:00Z",
											  "updatedAt": "2026-09-29T07:00:00Z"
											}
											"""))),
			@ApiResponse(
					responseCode = "400",
					description = "A path parameter is invalid",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Restaurant, ownership-scoped Inventory, or ownership-"
							+ "scoped Surplus Detection not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<FoodEligibilityEvaluationResponse> createEvaluation(
			@Parameter(description = "Restaurant public UUID", required = true)
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Parameter(description = "Inventory public UUID", required = true)
			@PathVariable("inventoryPublicId") UUID inventoryPublicId,
			@Parameter(description = "Surplus Detection public UUID", required = true)
			@PathVariable("surplusDetectionPublicId") UUID surplusDetectionPublicId) {
		FoodEligibilityEvaluationResponse response =
				evaluationService.evaluateAndPersist(
						restaurantPublicId,
						inventoryPublicId,
						surplusDetectionPublicId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
