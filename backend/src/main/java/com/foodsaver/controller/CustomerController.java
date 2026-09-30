package com.foodsaver.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

	private final CustomerService customerService;

	public CustomerController(CustomerService customerService) {
		this.customerService = customerService;
	}

	@PostMapping
	@Operation(
			summary = "Create a Customer",
			description = "Creates an ACTIVE Customer profile from validated contact data.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "201",
					description = "Customer successfully created",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = CustomerResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Request validation failed or request body is malformed",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class))),
			@ApiResponse(
					responseCode = "409",
					description = "A Customer with the canonical email already exists",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<CustomerResponse> createCustomer(
			@Valid @RequestBody CustomerCreateRequest request) {
		CustomerResponse response = customerService.createCustomer(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
