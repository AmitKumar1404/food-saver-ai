package com.foodsaver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foodsaver.dto.request.OfferCreateRequest;
import com.foodsaver.dto.response.OfferResponse;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.FoodEligibilityEvaluationNotFoundException;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.exception.OfferAlreadyExistsException;
import com.foodsaver.exception.OfferEligibilityException;
import com.foodsaver.service.OfferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@ExtendWith(MockitoExtension.class)
class OfferControllerTests {

	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID PRODUCT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID EVALUATION_PUBLIC_ID = UUID.randomUUID();
	private static final UUID OFFER_PUBLIC_ID = UUID.randomUUID();
	private static final Instant START_AT = Instant.parse("2026-09-29T12:00:00Z");
	private static final Instant EXPIRES_AT = Instant.parse("2026-09-29T14:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-09-29T12:00:01Z");
	private static final String ENDPOINT =
			"/api/v1/restaurants/{restaurantPublicId}/offers";

	@Mock
	private OfferService offerService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new OfferController(offerService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createsOfferAndDelegatesToService() throws Exception {
		when(offerService.createOffer(
				eq(RESTAURANT_PUBLIC_ID),
				any(OfferCreateRequest.class)))
				.thenReturn(response());

		mockMvc.perform(validRequest())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId").value(OFFER_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.restaurantPublicId")
						.value(RESTAURANT_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.productPublicId")
						.value(PRODUCT_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.inventoryPublicId")
						.value(INVENTORY_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.eligibilityEvaluationPublicId")
						.value(EVALUATION_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.originalPrice").value(100.0))
				.andExpect(jsonPath("$.discountPercentage").value(20.0))
				.andExpect(jsonPath("$.offerPrice").value(80.0))
				.andExpect(jsonPath("$.currencyCode").value("INR"))
				.andExpect(jsonPath("$.offeredQuantity").value(2.0))
				.andExpect(jsonPath("$.startAt").value(START_AT.toString()))
				.andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.inventoryId").doesNotExist())
				.andExpect(jsonPath("$.eligibilityEvaluationId").doesNotExist())
				.andExpect(jsonPath("$.safetyStatus").doesNotExist())
				.andExpect(jsonPath("$.aiMetadata").doesNotExist());

		ArgumentCaptor<OfferCreateRequest> requestCaptor =
				ArgumentCaptor.forClass(OfferCreateRequest.class);
		verify(offerService).createOffer(
				eq(RESTAURANT_PUBLIC_ID),
				requestCaptor.capture());
		OfferCreateRequest delegatedRequest = requestCaptor.getValue();
		assertEquals(EVALUATION_PUBLIC_ID,
				delegatedRequest.getEligibilityEvaluationPublicId());
		assertEquals(new BigDecimal("2.000"), delegatedRequest.getOfferedQuantity());
		assertEquals(new BigDecimal("20.00"), delegatedRequest.getDiscountPercentage());
		assertEquals(EXPIRES_AT, delegatedRequest.getExpiresAt());
	}

	@Test
	void returnsBadRequestForZeroOfferedQuantity() throws Exception {
		mockMvc.perform(request(validJson("0.000")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.offeredQuantity[0]")
						.value("Offered quantity must be greater than zero"))
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(offerService);
	}

	@Test
	void returnsBadRequestWhenEvaluationPublicIdIsMissing() throws Exception {
		String requestBody = """
				{
				  "offeredQuantity": 2.000,
				  "discountPercentage": 20.00,
				  "expiresAt": "%s"
				}
				""".formatted(EXPIRES_AT);

		mockMvc.perform(request(requestBody))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.eligibilityEvaluationPublicId[0]")
						.value("Eligibility evaluation public ID is required"));

		verifyNoInteractions(offerService);
	}

	@Test
	void returnsBadRequestForMalformedRestaurantUuid() throws Exception {
		mockMvc.perform(post(ENDPOINT, "not-a-uuid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(validJson("2.000")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid path parameter"))
				.andExpect(jsonPath("$.errors.restaurantPublicId[0]")
						.value("Must be a valid UUID"))
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(offerService);
	}

	@Test
	void returnsNotFoundWhenEvaluationIsMissingOrOwnedByAnotherRestaurant()
			throws Exception {
		arrangeFailure(new FoodEligibilityEvaluationNotFoundException(
				"Food eligibility evaluation not found"));

		mockMvc.perform(validRequest())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message")
						.value("Food eligibility evaluation not found"))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void returnsConflictForNotEligibleEvaluation() throws Exception {
		assertEligibilityConflict(
				"Food eligibility evaluation status is NOT_ELIGIBLE");
	}

	@Test
	void returnsConflictForRequiresReviewEvaluation() throws Exception {
		assertEligibilityConflict(
				"Food eligibility evaluation status is REQUIRES_REVIEW");
	}

	@Test
	void returnsConflictForStaleInventory() throws Exception {
		assertEligibilityConflict(
				"Food eligibility evaluation is stale for the current Inventory");
	}

	@Test
	void returnsConflictForDuplicateOrOpenOffer() throws Exception {
		arrangeFailure(new OfferAlreadyExistsException(
				"An open Offer already exists for the selected Inventory"));

		mockMvc.perform(validRequest())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message")
						.value("An open Offer already exists for the selected Inventory"))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void documentsOfferResponsesAndEligibilityGate() throws Exception {
		ApiResponses responses = OfferController.class
				.getMethod(
						"createOffer",
						UUID.class,
						OfferCreateRequest.class)
				.getAnnotation(ApiResponses.class);

		assertResponseSchema(responses, "201", OfferResponse.class);
		assertResponseSchema(responses, "400", ErrorResponse.class);
		assertResponseSchema(responses, "404", ErrorResponse.class);
		assertResponseSchema(responses, "409", ErrorResponse.class);

		Operation operation = OfferController.class
				.getMethod(
						"createOffer",
						UUID.class,
						OfferCreateRequest.class)
				.getAnnotation(Operation.class);
		assertTrue(operation.description().contains("ELIGIBLE_FOR_OFFER"));
	}

	private void assertEligibilityConflict(String message) throws Exception {
		arrangeFailure(new OfferEligibilityException(message));

		mockMvc.perform(validRequest())
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	private void arrangeFailure(RuntimeException exception) {
		when(offerService.createOffer(
				eq(RESTAURANT_PUBLIC_ID),
				any(OfferCreateRequest.class)))
				.thenThrow(exception);
	}

	private MockHttpServletRequestBuilder validRequest() {
		return request(validJson("2.000"));
	}

	private MockHttpServletRequestBuilder request(String body) {
		return post(ENDPOINT, RESTAURANT_PUBLIC_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private String validJson(String offeredQuantity) {
		return """
				{
				  "eligibilityEvaluationPublicId": "%s",
				  "offeredQuantity": %s,
				  "discountPercentage": 20.00,
				  "expiresAt": "%s"
				}
				""".formatted(
				EVALUATION_PUBLIC_ID,
				offeredQuantity,
				EXPIRES_AT);
	}

	private OfferResponse response() {
		OfferResponse response = new OfferResponse();
		response.setPublicId(OFFER_PUBLIC_ID);
		response.setRestaurantPublicId(RESTAURANT_PUBLIC_ID);
		response.setProductPublicId(PRODUCT_PUBLIC_ID);
		response.setInventoryPublicId(INVENTORY_PUBLIC_ID);
		response.setEligibilityEvaluationPublicId(EVALUATION_PUBLIC_ID);
		response.setOriginalPrice(new BigDecimal("100.00"));
		response.setDiscountPercentage(new BigDecimal("20.00"));
		response.setOfferPrice(new BigDecimal("80.00"));
		response.setCurrencyCode("INR");
		response.setOfferedQuantity(new BigDecimal("2.000"));
		response.setStartAt(START_AT);
		response.setExpiresAt(EXPIRES_AT);
		response.setStatus(OfferStatus.ACTIVE);
		response.setCreatedAt(CREATED_AT);
		response.setUpdatedAt(CREATED_AT);
		return response;
	}

	private void assertResponseSchema(
			ApiResponses responses,
			String responseCode,
			Class<?> expectedSchema) {
		ApiResponse response = Arrays.stream(responses.value())
				.filter(candidate -> responseCode.equals(candidate.responseCode()))
				.findFirst()
				.orElseThrow();
		assertEquals(
				expectedSchema,
				response.content()[0].schema().implementation());
	}
}
