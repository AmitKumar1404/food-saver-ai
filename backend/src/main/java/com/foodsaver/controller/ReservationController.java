package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.InvalidIdempotencyKeyException;
import com.foodsaver.service.ReservationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/customers/{customerPublicId}/reservations")
public class ReservationController {

	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final ReservationService reservationService;

	public ReservationController(ReservationService reservationService) {
		this.reservationService = reservationService;
	}

	@PostMapping
	@Operation(
			summary = "Create a Reservation",
			description = "Creates an ACTIVE marketplace Reservation from an existing "
					+ "Offer. Pricing, expiry, and idempotency behavior are delegated "
					+ "to the Reservation service. Reservation lifecycle status is not "
					+ "food-safety certification.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Reservation created successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ReservationResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Invalid request, path parameter, request body, or "
							+ "Idempotency-Key",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Customer or Offer not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Reservation lifecycle, validation, or idempotency conflict",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<ReservationResponse> createReservation(
			@Parameter(description = "Customer public UUID", required = true)
			@PathVariable("customerPublicId") UUID customerPublicId,
			@Valid @RequestBody ReservationCreateRequest request,
			@Parameter(
					name = "Idempotency-Key",
					description = "Required create-operation idempotency key",
					required = true,
					in = ParameterIn.HEADER,
					schema = @Schema(maxLength = IDEMPOTENCY_KEY_MAX_LENGTH))
			@RequestHeader(name = "Idempotency-Key", required = false)
			String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		ReservationResponse response = reservationService.createReservation(
				customerPublicId,
				request,
				idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new InvalidIdempotencyKeyException(
					"Idempotency-Key header is required");
		}
		if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new InvalidIdempotencyKeyException(
					"Idempotency-Key header must not exceed 100 characters");
		}
	}
}
