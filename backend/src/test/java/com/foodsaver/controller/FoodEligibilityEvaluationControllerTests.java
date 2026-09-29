package com.foodsaver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;
import com.foodsaver.dto.response.FoodEligibilityRuleResultResponse;
import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.exception.InventoryNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.exception.SurplusDetectionNotFoundException;
import com.foodsaver.service.FoodEligibilityEvaluationService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@ExtendWith(MockitoExtension.class)
class FoodEligibilityEvaluationControllerTests {

	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID DETECTION_PUBLIC_ID = UUID.randomUUID();
	private static final UUID EVALUATION_PUBLIC_ID = UUID.randomUUID();
	private static final String ENDPOINT =
			"/api/v1/restaurants/{restaurantPublicId}/inventory/{inventoryPublicId}"
					+ "/surplus-detections/{surplusDetectionPublicId}"
					+ "/eligibility-evaluations";

	@Mock
	private FoodEligibilityEvaluationService evaluationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(
						new FoodEligibilityEvaluationController(evaluationService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createsEligibleEvaluationWithoutRequestBody() throws Exception {
		when(evaluationService.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.thenReturn(response(FoodEligibilityStatus.ELIGIBLE_FOR_OFFER));

		mockMvc.perform(post(
				ENDPOINT,
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId")
						.value(EVALUATION_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.restaurantPublicId")
						.value(RESTAURANT_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.inventoryPublicId")
						.value(INVENTORY_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.surplusDetectionPublicId")
						.value(DETECTION_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.policyKey").value("FOOD_ELIGIBILITY_V1"))
				.andExpect(jsonPath("$.policyVersion").value("1.0"))
				.andExpect(jsonPath("$.policySourceReference")
						.value("FOODSAVER_INTERNAL_V1_POLICY"))
				.andExpect(jsonPath("$.status").value("ELIGIBLE_FOR_OFFER"))
				.andExpect(jsonPath("$.evaluatedInventoryVersion").value(3))
				.andExpect(jsonPath("$.evaluatedAvailableQuantity").value(8.0))
				.andExpect(jsonPath("$.ruleResults.length()").value(2))
				.andExpect(jsonPath("$.ruleResults[0].ruleCode")
						.value("OWNERSHIP_CONSISTENCY"))
				.andExpect(jsonPath("$.ruleResults[0].outcome").value("PASS"))
				.andExpect(jsonPath("$.ruleResults[1].ruleCode")
						.value("POLICY_AVAILABILITY"));

		verify(evaluationService).evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID);
	}

	@Test
	void returnsCreatedForNotEligibleOutcome() throws Exception {
		arrangeResponse(FoodEligibilityStatus.NOT_ELIGIBLE);

		mockMvc.perform(request())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("NOT_ELIGIBLE"));
	}

	@Test
	void returnsCreatedForRequiresReviewOutcome() throws Exception {
		arrangeResponse(FoodEligibilityStatus.REQUIRES_REVIEW);

		mockMvc.perform(request())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"));
	}

	@Test
	void returnsNotFoundWhenRestaurantDoesNotExist() throws Exception {
		when(evaluationService.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.thenThrow(new RestaurantNotFoundException(RESTAURANT_PUBLIC_ID));

		mockMvc.perform(request()).andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundWhenInventoryDoesNotExist() throws Exception {
		arrangeInventoryNotFound("Inventory not found");

		mockMvc.perform(request()).andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundWhenSurplusDetectionDoesNotExist() throws Exception {
		when(evaluationService.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.thenThrow(new SurplusDetectionNotFoundException(
						"Surplus detection not found"));

		mockMvc.perform(request()).andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundForOwnershipMismatch() throws Exception {
		arrangeInventoryNotFound("Inventory not found for restaurant");

		mockMvc.perform(request()).andExpect(status().isNotFound());
	}

	@Test
	void returnsBadRequestForInvalidPathParameter() throws Exception {
		mockMvc.perform(post(
				ENDPOINT,
				"not-a-uuid",
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid path parameter"))
				.andExpect(jsonPath("$.path").value(
						"/api/v1/restaurants/not-a-uuid/inventory/"
								+ INVENTORY_PUBLIC_ID
								+ "/surplus-detections/"
								+ DETECTION_PUBLIC_ID
								+ "/eligibility-evaluations"))
				.andExpect(jsonPath("$.errors.restaurantPublicId[0]")
						.value("Must be a valid UUID"))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void documentsBadRequestWithErrorResponseSchema() throws Exception {
		ApiResponses responses = FoodEligibilityEvaluationController.class
				.getMethod(
						"createEvaluation",
						UUID.class,
						UUID.class,
						UUID.class)
				.getAnnotation(ApiResponses.class);
		ApiResponse badRequestResponse = Arrays.stream(responses.value())
				.filter(response -> "400".equals(response.responseCode()))
				.findFirst()
				.orElseThrow();

		assertEquals(
				ErrorResponse.class,
				badRequestResponse.content()[0].schema().implementation());
	}

	private void arrangeResponse(FoodEligibilityStatus status) {
		when(evaluationService.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID)).thenReturn(response(status));
	}

	private void arrangeInventoryNotFound(String message) {
		when(evaluationService.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID))
				.thenThrow(new InventoryNotFoundException(message));
	}

	private MockHttpServletRequestBuilder request() {
		return post(
				ENDPOINT,
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID);
	}

	private FoodEligibilityEvaluationResponse response(
			FoodEligibilityStatus status) {
		FoodEligibilityEvaluationResponse response =
				new FoodEligibilityEvaluationResponse();
		response.setPublicId(EVALUATION_PUBLIC_ID);
		response.setRestaurantPublicId(RESTAURANT_PUBLIC_ID);
		response.setInventoryPublicId(INVENTORY_PUBLIC_ID);
		response.setSurplusDetectionPublicId(DETECTION_PUBLIC_ID);
		response.setPolicyKey("FOOD_ELIGIBILITY_V1");
		response.setPolicyVersion("1.0");
		response.setPolicySourceReference("FOODSAVER_INTERNAL_V1_POLICY");
		response.setStatus(status);
		response.setEvaluatedInventoryVersion(3L);
		response.setEvaluatedAvailableQuantity(new BigDecimal("8.000"));
		response.setEvaluatedAt(Instant.parse("2026-09-29T07:00:00Z"));
		response.setRuleResults(List.of(
				ruleResponse(
						"OWNERSHIP_CONSISTENCY",
						FoodEligibilityRuleOutcome.PASS,
						null),
				ruleResponse(
						"POLICY_AVAILABILITY",
						FoodEligibilityRuleOutcome.PASS,
						null)));
		response.setCreatedAt(Instant.parse("2026-09-29T07:00:01Z"));
		response.setUpdatedAt(Instant.parse("2026-09-29T07:00:01Z"));
		return response;
	}

	private FoodEligibilityRuleResultResponse ruleResponse(
			String ruleCode,
			FoodEligibilityRuleOutcome outcome,
			FoodEligibilityReasonCode reasonCode) {
		FoodEligibilityRuleResultResponse response =
				new FoodEligibilityRuleResultResponse();
		response.setRuleCode(ruleCode);
		response.setOutcome(outcome);
		response.setReasonCode(reasonCode);
		response.setMessage("Rule result");
		return response;
	}
}
