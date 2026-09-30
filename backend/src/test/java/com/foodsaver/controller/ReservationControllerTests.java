package com.foodsaver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
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

import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.exception.OfferNotFoundException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.exception.ReservationValidationException;
import com.foodsaver.service.ReservationService;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTests {

	private static final String ENDPOINT =
			"/api/v1/customers/{customerPublicId}/reservations";
	private static final String RESOLVED_ENDPOINT_PREFIX = "/api/v1/customers/";
	private static final String IDEMPOTENCY_KEY = "reservation-create-1";
	private static final UUID CUSTOMER_PUBLIC_ID = UUID.randomUUID();
	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID OFFER_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID RESERVATION_PUBLIC_ID = UUID.randomUUID();
	private static final Instant EXPIRES_AT = Instant.parse("2026-09-30T09:00:00Z");
	private static final Instant CREATED_AT = Instant.parse("2026-09-30T08:45:00Z");

	@Mock
	private ReservationService reservationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new ReservationController(reservationService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createsReservationAndDelegatesExactInputsToService() throws Exception {
		when(reservationService.createReservation(
				eq(CUSTOMER_PUBLIC_ID),
				any(ReservationCreateRequest.class),
				eq(IDEMPOTENCY_KEY)))
				.thenReturn(response());

		mockMvc.perform(validRequest())
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId")
						.value(RESERVATION_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.customerPublicId")
						.value(CUSTOMER_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.restaurantPublicId")
						.value(RESTAURANT_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.offerPublicId")
						.value(OFFER_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.inventoryPublicId")
						.value(INVENTORY_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.quantity").value(2.125))
				.andExpect(jsonPath("$.unitPrice").value(80.25))
				.andExpect(jsonPath("$.totalAmount").value(170.53))
				.andExpect(jsonPath("$.currencyCode").value("INR"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.idempotencyKey").doesNotExist())
				.andExpect(jsonPath("$.requestHash").doesNotExist())
				.andExpect(jsonPath("$.customerId").doesNotExist())
				.andExpect(jsonPath("$.restaurantId").doesNotExist())
				.andExpect(jsonPath("$.offerId").doesNotExist())
				.andExpect(jsonPath("$.inventoryId").doesNotExist());

		ArgumentCaptor<ReservationCreateRequest> requestCaptor =
				ArgumentCaptor.forClass(ReservationCreateRequest.class);
		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		verify(reservationService).createReservation(
				eq(CUSTOMER_PUBLIC_ID),
				requestCaptor.capture(),
				keyCaptor.capture());
		assertEquals(OFFER_PUBLIC_ID, requestCaptor.getValue().getOfferPublicId());
		assertEquals(
				new BigDecimal("2.125"),
				requestCaptor.getValue().getQuantity());
		assertEquals(IDEMPOTENCY_KEY, keyCaptor.getValue());
	}

	@Test
	void returnsBadRequestWhenIdempotencyKeyIsMissing() throws Exception {
		mockMvc.perform(request(validJson()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message")
						.value("Idempotency-Key header is required"))
				.andExpect(jsonPath("$.path").value(resolvedEndpoint()))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsBadRequestWhenIdempotencyKeyIsBlank() throws Exception {
		mockMvc.perform(request(validJson()).header("Idempotency-Key", "   "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message")
						.value("Idempotency-Key header is required"));

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsBadRequestWhenIdempotencyKeyExceeds100Characters()
			throws Exception {
		mockMvc.perform(request(validJson())
				.header("Idempotency-Key", "k".repeat(101)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message")
						.value("Idempotency-Key header must not exceed 100 characters"));

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsBadRequestForInvalidCustomerPublicId() throws Exception {
		mockMvc.perform(post(ENDPOINT, "not-a-uuid")
				.header("Idempotency-Key", IDEMPOTENCY_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(validJson()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid path parameter"))
				.andExpect(jsonPath("$.errors.customerPublicId[0]")
						.value("Must be a valid UUID"))
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsBadRequestForInvalidRequestBody() throws Exception {
		String body = """
				{
				  "offerPublicId": "%s",
				  "quantity": 0.000
				}
				""".formatted(OFFER_PUBLIC_ID);

		mockMvc.perform(request(body).header("Idempotency-Key", IDEMPOTENCY_KEY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.errors.quantity")
						.value(hasItem("Reservation quantity must be greater than zero")))
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsErrorResponseForMalformedJson() throws Exception {
		String body = """
				{
				  "offerPublicId": "%s",
				  "quantity": 2.125
				""".formatted(OFFER_PUBLIC_ID);

		mockMvc.perform(request(body).header("Idempotency-Key", IDEMPOTENCY_KEY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Malformed request body"))
				.andExpect(jsonPath("$.path").value(resolvedEndpoint()))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(reservationService);
	}

	@Test
	void returnsNotFoundWhenCustomerDoesNotExist() throws Exception {
		CustomerNotFoundException exception =
				new CustomerNotFoundException(CUSTOMER_PUBLIC_ID);
		arrangeFailure(exception);

		assertErrorResponse(404, exception.getMessage());
	}

	@Test
	void returnsNotFoundWhenOfferDoesNotExist() throws Exception {
		OfferNotFoundException exception = new OfferNotFoundException(OFFER_PUBLIC_ID);
		arrangeFailure(exception);

		assertErrorResponse(404, exception.getMessage());
	}

	@Test
	void returnsConflictForReservationValidationFailure() throws Exception {
		String message = "Offer is logically expired";
		arrangeFailure(new ReservationValidationException(message));

		assertErrorResponse(409, message);
	}

	@Test
	void returnsConflictForIdempotencyKeyPayloadMismatch() throws Exception {
		String message =
				"Idempotency key was already used for a different Reservation request";
		arrangeFailure(new ReservationIdempotencyConflictException(message));

		assertErrorResponse(409, message);
	}

	@Test
	void documentsReservationCreateResponsesAndRequiredHeader() throws Exception {
		java.lang.reflect.Method method = ReservationController.class.getMethod(
				"createReservation",
				UUID.class,
				ReservationCreateRequest.class,
				String.class);
		ApiResponses responses = method.getAnnotation(ApiResponses.class);

		assertResponseSchema(responses, "201", ReservationResponse.class);
		assertResponseSchema(responses, "400", ErrorResponse.class);
		assertResponseSchema(responses, "404", ErrorResponse.class);
		assertResponseSchema(responses, "409", ErrorResponse.class);

		Parameter header = method.getParameters()[2].getAnnotation(Parameter.class);
		assertEquals("Idempotency-Key", header.name());
		assertEquals(ParameterIn.HEADER, header.in());
		assertTrue(header.required());
		assertEquals(100, header.schema().maxLength());
	}

	private void arrangeFailure(RuntimeException exception) {
		when(reservationService.createReservation(
				eq(CUSTOMER_PUBLIC_ID),
				any(ReservationCreateRequest.class),
				eq(IDEMPOTENCY_KEY)))
				.thenThrow(exception);
	}

	private void assertErrorResponse(int expectedStatus, String message)
			throws Exception {
		mockMvc.perform(validRequest())
				.andExpect(status().is(expectedStatus))
				.andExpect(jsonPath("$.status").value(expectedStatus))
				.andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.path").value(resolvedEndpoint()))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	private MockHttpServletRequestBuilder validRequest() {
		return request(validJson()).header("Idempotency-Key", IDEMPOTENCY_KEY);
	}

	private MockHttpServletRequestBuilder request(String body) {
		return post(ENDPOINT, CUSTOMER_PUBLIC_ID)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private String validJson() {
		return """
				{
				  "offerPublicId": "%s",
				  "quantity": 2.125
				}
				""".formatted(OFFER_PUBLIC_ID);
	}

	private String resolvedEndpoint() {
		return RESOLVED_ENDPOINT_PREFIX
				+ CUSTOMER_PUBLIC_ID
				+ "/reservations";
	}

	private ReservationResponse response() {
		ReservationResponse response = new ReservationResponse();
		response.setPublicId(RESERVATION_PUBLIC_ID);
		response.setCustomerPublicId(CUSTOMER_PUBLIC_ID);
		response.setRestaurantPublicId(RESTAURANT_PUBLIC_ID);
		response.setOfferPublicId(OFFER_PUBLIC_ID);
		response.setInventoryPublicId(INVENTORY_PUBLIC_ID);
		response.setQuantity(new BigDecimal("2.125"));
		response.setUnitPrice(new BigDecimal("80.25"));
		response.setTotalAmount(new BigDecimal("170.53"));
		response.setCurrencyCode("INR");
		response.setStatus(ReservationStatus.ACTIVE);
		response.setExpiresAt(EXPIRES_AT);
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
