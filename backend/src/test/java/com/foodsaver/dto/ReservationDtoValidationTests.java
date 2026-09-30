package com.foodsaver.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ReservationDtoValidationTests {

	private final Validator validator =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void acceptsValidReservationRequest() {
		assertTrue(validator.validate(validRequest()).isEmpty());
	}

	@Test
	void rejectsMissingOfferPublicId() {
		ReservationCreateRequest request = validRequest();
		request.setOfferPublicId(null);

		assertViolation(
				request,
				"offerPublicId",
				"Offer public ID is required");
	}

	@Test
	void rejectsMissingOrNonPositiveQuantity() {
		ReservationCreateRequest request = validRequest();
		request.setQuantity(null);
		assertViolation(
				request,
				"quantity",
				"Reservation quantity is required");

		request.setQuantity(BigDecimal.ZERO);
		assertViolation(
				request,
				"quantity",
				"Reservation quantity must be greater than zero");
	}

	@Test
	void rejectsQuantityOutsideDatabasePrecision() {
		ReservationCreateRequest request = validRequest();
		request.setQuantity(new BigDecimal("1000000000.000"));
		assertViolation(
				request,
				"quantity",
				"Reservation quantity must have up to 9 integer and 3 fractional digits");

		request.setQuantity(new BigDecimal("1.0001"));
		assertViolation(
				request,
				"quantity",
				"Reservation quantity must have up to 9 integer and 3 fractional digits");
	}

	@Test
	void requestContainsOnlyOfferAndQuantity() {
		JsonNode request = new ObjectMapper().valueToTree(new ReservationCreateRequest());

		assertTrue(request.has("offerPublicId"));
		assertTrue(request.has("quantity"));
		assertEquals(2, request.size());
	}

	@Test
	void responseExposesOnlyApprovedReservationFields() {
		JsonNode response = new ObjectMapper().valueToTree(new ReservationResponse());

		assertTrue(response.has("publicId"));
		assertTrue(response.has("customerPublicId"));
		assertTrue(response.has("restaurantPublicId"));
		assertTrue(response.has("offerPublicId"));
		assertTrue(response.has("inventoryPublicId"));
		assertTrue(response.has("quantity"));
		assertTrue(response.has("unitPrice"));
		assertTrue(response.has("totalAmount"));
		assertTrue(response.has("currencyCode"));
		assertTrue(response.has("status"));
		assertTrue(response.has("expiresAt"));
		assertTrue(response.has("createdAt"));
		assertTrue(response.has("updatedAt"));
		assertFalse(response.has("id"));
		assertFalse(response.has("version"));
		assertFalse(response.has("idempotencyKey"));
		assertFalse(response.has("requestHash"));
		assertEquals(13, response.size());
	}

	private ReservationCreateRequest validRequest() {
		ReservationCreateRequest request = new ReservationCreateRequest();
		request.setOfferPublicId(UUID.randomUUID());
		request.setQuantity(new BigDecimal("1.250"));
		return request;
	}

	private void assertViolation(
			ReservationCreateRequest request,
			String property,
			String message) {
		Set<ConstraintViolation<ReservationCreateRequest>> violations =
				validator.validate(request);

		assertTrue(violations.stream().anyMatch(violation ->
				property.equals(violation.getPropertyPath().toString())
						&& message.equals(violation.getMessage())));
	}
}
