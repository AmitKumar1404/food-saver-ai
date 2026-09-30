package com.foodsaver.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.response.OrderItemResponse;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.enums.OrderStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.exception.OrderCompletionConflictException;
import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderNotFoundException;
import com.foodsaver.exception.ReservationNotFoundException;
import com.foodsaver.service.OrderService;

@ExtendWith(MockitoExtension.class)
class OrderControllerTests {

	private static final UUID CUSTOMER_ID = UUID.randomUUID();
	private static final UUID OTHER_CUSTOMER_ID = UUID.randomUUID();
	private static final UUID ORDER_ID = UUID.randomUUID();
	private static final UUID RESERVATION_ID = UUID.randomUUID();
	private static final String KEY = "order-create-1";

	@Mock
	private OrderService orderService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new OrderController(orderService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createsOneItemOrderWithoutInternalIds() throws Exception {
		when(orderService.createOrder(
				eq(CUSTOMER_ID),
				any(OrderCreateRequest.class),
				eq(KEY)))
				.thenReturn(response());

		mockMvc.perform(post("/api/v1/customers/{customerId}/orders", CUSTOMER_ID)
						.header("Idempotency-Key", KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reservationPublicId":"%s"}
								""".formatted(RESERVATION_ID)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId").value(ORDER_ID.toString()))
				.andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.item.reservationPublicId")
						.value(RESERVATION_ID.toString()))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist());
	}

	@Test
	void getsCustomerOwnedOrder() throws Exception {
		when(orderService.getOrder(CUSTOMER_ID, ORDER_ID))
				.thenReturn(response());

		mockMvc.perform(get(
						"/api/v1/customers/{customerId}/orders/{orderId}",
						CUSTOMER_ID,
						ORDER_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.publicId").value(ORDER_ID.toString()));
	}

	@Test
	void completesOrderAndReturnsPublicResponse() throws Exception {
		OrderResponse completed = response();
		completed.setStatus(OrderStatus.COMPLETED);
		completed.setCompletedAt(Instant.parse("2026-09-30T08:01:00Z"));
		completed.setUpdatedAt(completed.getCompletedAt());
		when(orderService.completeOrder(CUSTOMER_ID, ORDER_ID))
				.thenReturn(completed);

		mockMvc.perform(post(
						"/api/v1/customers/{customerId}/orders/{orderId}/complete",
						CUSTOMER_ID,
						ORDER_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"completedAt":"2000-01-01T00:00:00Z"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.publicId").value(ORDER_ID.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.completedAt")
						.value("2026-09-30T08:01:00Z"))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist());
		verify(orderService).completeOrder(CUSTOMER_ID, ORDER_ID);
	}

	@Test
	void mapsCompletionPathAndConflictErrorsSafely() throws Exception {
		mockMvc.perform(post(
						"/api/v1/customers/not-a-uuid/orders/{orderId}/complete",
						ORDER_ID))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(
						"/api/v1/customers/{customerId}/orders/not-a-uuid/complete",
						CUSTOMER_ID))
				.andExpect(status().isBadRequest());

		when(orderService.completeOrder(CUSTOMER_ID, ORDER_ID))
				.thenThrow(new OrderCompletionConflictException(
						"Order completion conflicts with current persisted state"));
		mockMvc.perform(post(
						"/api/v1/customers/{customerId}/orders/{orderId}/complete",
						CUSTOMER_ID,
						ORDER_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(
						"Order completion conflicts with current persisted state"))
				.andExpect(jsonPath("$.reserved").doesNotExist())
				.andExpect(jsonPath("$.sql").doesNotExist())
				.andExpect(jsonPath("$.trace").doesNotExist());
	}

	@Test
	void mapsCompletionScopeFailuresToSanitizedNotFoundResponses()
			throws Exception {
		UUID missingCustomerId = UUID.randomUUID();
		UUID missingOrderId = UUID.randomUUID();
		when(orderService.completeOrder(missingCustomerId, ORDER_ID))
				.thenThrow(new CustomerNotFoundException(missingCustomerId));
		when(orderService.completeOrder(CUSTOMER_ID, missingOrderId))
				.thenThrow(new OrderNotFoundException(missingOrderId));
		when(orderService.completeOrder(OTHER_CUSTOMER_ID, ORDER_ID))
				.thenThrow(new OrderNotFoundException(ORDER_ID));

		assertSanitizedCompletionNotFound(missingCustomerId, ORDER_ID);
		assertSanitizedCompletionNotFound(CUSTOMER_ID, missingOrderId);
		assertSanitizedCompletionNotFound(OTHER_CUSTOMER_ID, ORDER_ID);
	}

	@Test
	void rejectsMissingHeaderAndReservationId() throws Exception {
		mockMvc.perform(post("/api/v1/customers/{customerId}/orders", CUSTOMER_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(orderService);
	}

	@Test
	void mapsScopedNotFoundAndConversionConflict() throws Exception {
		when(orderService.createOrder(
				eq(CUSTOMER_ID),
				any(OrderCreateRequest.class),
				eq(KEY)))
				.thenThrow(new ReservationNotFoundException(RESERVATION_ID));

		mockMvc.perform(post("/api/v1/customers/{customerId}/orders", CUSTOMER_ID)
						.header("Idempotency-Key", KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reservationPublicId":"%s"}
								""".formatted(RESERVATION_ID)))
				.andExpect(status().isNotFound());

		when(orderService.getOrder(CUSTOMER_ID, ORDER_ID))
				.thenThrow(new OrderNotFoundException(ORDER_ID));
		mockMvc.perform(get(
						"/api/v1/customers/{customerId}/orders/{orderId}",
						CUSTOMER_ID,
						ORDER_ID))
				.andExpect(status().isNotFound());

		when(orderService.createOrder(
				eq(CUSTOMER_ID),
				any(OrderCreateRequest.class),
				eq("conflict")))
				.thenThrow(new OrderConversionConflictException("Order conflict"));
		mockMvc.perform(post("/api/v1/customers/{customerId}/orders", CUSTOMER_ID)
						.header("Idempotency-Key", "conflict")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reservationPublicId":"%s"}
								""".formatted(RESERVATION_ID)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Order conflict"));
	}

	private OrderResponse response() {
		OrderItemResponse item = new OrderItemResponse();
		item.setPublicId(UUID.randomUUID());
		item.setReservationPublicId(RESERVATION_ID);
		item.setOfferPublicId(UUID.randomUUID());
		item.setProductPublicId(UUID.randomUUID());
		item.setInventoryPublicId(UUID.randomUUID());
		item.setProductNameSnapshot("Snapshot");
		item.setQuantity(new BigDecimal("1.000"));
		item.setUnitPrice(new BigDecimal("80.00"));
		item.setTotalAmount(new BigDecimal("80.00"));
		item.setCurrencyCode("INR");
		item.setCreatedAt(Instant.parse("2026-09-30T08:00:00Z"));

		OrderResponse response = new OrderResponse();
		response.setPublicId(ORDER_ID);
		response.setCustomerPublicId(CUSTOMER_ID);
		response.setRestaurantPublicId(UUID.randomUUID());
		response.setStatus(OrderStatus.CONFIRMED);
		response.setTotalAmount(new BigDecimal("80.00"));
		response.setCurrencyCode("INR");
		response.setConfirmedAt(Instant.parse("2026-09-30T08:00:00Z"));
		response.setCreatedAt(Instant.parse("2026-09-30T08:00:00Z"));
		response.setUpdatedAt(Instant.parse("2026-09-30T08:00:00Z"));
		response.setItem(item);
		return response;
	}

	private void assertSanitizedCompletionNotFound(
			UUID customerPublicId,
			UUID orderPublicId) throws Exception {
		mockMvc.perform(post(
						"/api/v1/customers/{customerId}/orders/{orderId}/complete",
						customerPublicId,
						orderPublicId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.reserved").doesNotExist())
				.andExpect(jsonPath("$.sql").doesNotExist())
				.andExpect(jsonPath("$.trace").doesNotExist());
	}
}
