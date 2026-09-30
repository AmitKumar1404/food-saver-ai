package com.foodsaver.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class CustomerDtoValidationTests {

	private final Validator validator =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void acceptsValidRequestWithoutChangingEmailCase() {
		CustomerCreateRequest request = validRequest();

		assertTrue(validator.validate(request).isEmpty());
		assertEquals("Customer.Name@Example.COM", request.getEmail());
	}

	@Test
	void rejectsMissingEmail() {
		CustomerCreateRequest request = validRequest();
		request.setEmail(null);

		assertViolation(
				request,
				"email",
				"Customer email is required");
	}

	@Test
	void rejectsBlankEmail() {
		CustomerCreateRequest request = validRequest();
		request.setEmail("   ");

		assertViolation(
				request,
				"email",
				"Customer email is required");
	}

	@Test
	void rejectsInvalidEmail() {
		CustomerCreateRequest request = validRequest();
		request.setEmail("not-an-email");

		assertViolation(
				request,
				"email",
				"Customer email must be a valid email address");
	}

	@Test
	void rejectsEmailLongerThan254Characters() {
		CustomerCreateRequest request = validRequest();
		request.setEmail("a".repeat(243) + "@example.com");

		assertViolation(
				request,
				"email",
				"Customer email must not exceed 254 characters");
	}

	@Test
	void rejectsMissingDisplayName() {
		CustomerCreateRequest request = validRequest();
		request.setDisplayName(null);

		assertViolation(
				request,
				"displayName",
				"Display name is required");
	}

	@Test
	void rejectsBlankDisplayName() {
		CustomerCreateRequest request = validRequest();
		request.setDisplayName("   ");

		assertViolation(
				request,
				"displayName",
				"Display name is required");
	}

	@Test
	void rejectsDisplayNameLongerThan100Characters() {
		CustomerCreateRequest request = validRequest();
		request.setDisplayName("a".repeat(101));

		assertViolation(
				request,
				"displayName",
				"Display name must not exceed 100 characters");
	}

	@Test
	void acceptsMissingContactPhone() {
		CustomerCreateRequest request = validRequest();
		request.setContactPhone(null);

		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void rejectsContactPhoneLongerThan32Characters() {
		CustomerCreateRequest request = validRequest();
		request.setContactPhone("1".repeat(33));

		assertViolation(
				request,
				"contactPhone",
				"Contact phone must not exceed 32 characters");
	}

	@Test
	void responseExposesOnlyApprovedCustomerFields() {
		JsonNode response = new ObjectMapper().valueToTree(new CustomerResponse());

		assertTrue(response.has("publicId"));
		assertTrue(response.has("email"));
		assertTrue(response.has("displayName"));
		assertTrue(response.has("contactPhone"));
		assertTrue(response.has("status"));
		assertTrue(response.has("createdAt"));
		assertTrue(response.has("updatedAt"));
		assertFalse(response.has("id"));
		assertFalse(response.has("version"));
		assertEquals(7, response.size());
	}

	private CustomerCreateRequest validRequest() {
		CustomerCreateRequest request = new CustomerCreateRequest();
		request.setEmail("Customer.Name@Example.COM");
		request.setDisplayName("Customer Name");
		request.setContactPhone("+14155552671");
		return request;
	}

	private void assertViolation(
			CustomerCreateRequest request,
			String property,
			String message) {
		Set<ConstraintViolation<CustomerCreateRequest>> violations =
				validator.validate(request);

		assertTrue(violations.stream().anyMatch(violation ->
				property.equals(violation.getPropertyPath().toString())
						&& message.equals(violation.getMessage())));
	}
}
