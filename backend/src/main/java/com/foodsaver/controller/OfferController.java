package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.OfferCreateRequest;
import com.foodsaver.dto.response.OfferResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.service.OfferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantPublicId}/offers")
public class OfferController {

	private final OfferService offerService;

	public OfferController(OfferService offerService) {
		this.offerService = offerService;
	}

	@PostMapping
	@Operation(
			summary = "Create an Offer",
			description = "Creates a marketplace Offer only from the explicitly selected "
					+ "persisted Food Eligibility Evaluation whose status is "
					+ "ELIGIBLE_FOR_OFFER. Offer lifecycle status is not food-safety "
					+ "certification.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Offer created successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = OfferResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Request validation or path parameter failed",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Restaurant or ownership-scoped eligibility evaluation "
							+ "not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Eligibility, current source state, or Offer conflict "
							+ "prevents creation",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<OfferResponse> createOffer(
			@Parameter(description = "Restaurant public UUID", required = true)
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Valid @RequestBody OfferCreateRequest request) {
		OfferResponse response = offerService.createOffer(restaurantPublicId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
