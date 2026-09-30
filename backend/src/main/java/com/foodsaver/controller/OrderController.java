package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.InvalidIdempotencyKeyException;
import com.foodsaver.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/customers/{customerPublicId}/orders")
public class OrderController {

	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	@Operation(summary = "Create an Order from one active Reservation")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Order confirmed successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Invalid request or Idempotency-Key",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Customer or Reservation not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Reservation, ledger, idempotency, or concurrency conflict",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<OrderResponse> createOrder(
			@PathVariable("customerPublicId") UUID customerPublicId,
			@Valid @RequestBody OrderCreateRequest request,
			@Parameter(
					name = "Idempotency-Key",
					required = true,
					in = ParameterIn.HEADER,
					schema = @Schema(maxLength = IDEMPOTENCY_KEY_MAX_LENGTH))
			@RequestHeader(name = "Idempotency-Key", required = false)
			String idempotencyKey) {
		validateIdempotencyKey(idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(
				orderService.createOrder(
						customerPublicId,
						request,
						idempotencyKey));
	}

	@GetMapping("/{orderPublicId}")
	@Operation(summary = "Get one Customer-owned Order")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Order retrieved successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Invalid public UUID",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Order not found in Customer scope",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<OrderResponse> getOrder(
			@PathVariable("customerPublicId") UUID customerPublicId,
			@PathVariable("orderPublicId") UUID orderPublicId) {
		return ResponseEntity.ok(
				orderService.getOrder(customerPublicId, orderPublicId));
	}

	@PostMapping("/{orderPublicId}/complete")
	@Operation(summary = "Complete one confirmed Order")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Order completed or replayed successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = OrderResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Invalid public UUID",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "404",
					description = "Customer or customer-owned Order not found",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "Order lifecycle, ledger, or concurrency conflict",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<OrderResponse> completeOrder(
			@PathVariable("customerPublicId") UUID customerPublicId,
			@PathVariable("orderPublicId") UUID orderPublicId) {
		return ResponseEntity.ok(
				orderService.completeOrder(customerPublicId, orderPublicId));
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
