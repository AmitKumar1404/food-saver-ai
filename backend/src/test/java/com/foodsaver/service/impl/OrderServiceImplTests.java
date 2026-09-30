package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import com.foodsaver.config.ReservationAllocationActivation;
import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.exception.OrderCompletionConflictException;
import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderIdempotencyRaceException;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.repository.OrderItemRepository;
import com.foodsaver.repository.OrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTests {

	private static final UUID CUSTOMER_ID = UUID.randomUUID();
	private static final UUID RESERVATION_ID = UUID.randomUUID();
	private static final String KEY = "order-create-1";

	@Mock
	private OrderConversionCommand conversionCommand;
	@Mock
	private OrderCompletionCommand completionCommand;
	@Mock
	private OrderReplayService replayService;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderItemRepository orderItemRepository;
	@Mock
	private OrderResponseMapper responseMapper;

	private OrderServiceImpl service;

	@BeforeEach
	void setUp() {
		ReservationAllocationActivation activation =
				new ReservationAllocationActivation();
		activation.activate();
		service = new OrderServiceImpl(
				activation,
				conversionCommand,
				completionCommand,
				replayService,
				orderRepository,
				orderItemRepository,
				responseMapper);
	}

	@Test
	void delegatesConversionAndReturnsResponse() {
		OrderResponse expected = new OrderResponse();
		when(conversionCommand.convert(any())).thenReturn(expected);

		assertSame(
				expected,
				service.createOrder(CUSTOMER_ID, request(), KEY));
	}

	@Test
	void translatesInitialLockFailure() {
		when(conversionCommand.convert(any()))
				.thenThrow(new CannotAcquireLockException("timeout"));

		assertThrows(
				OrderConversionConflictException.class,
				() -> service.createOrder(CUSTOMER_ID, request(), KEY));
	}

	@Test
	void delegatesCompletionAndReturnsResponse() {
		UUID orderPublicId = UUID.randomUUID();
		OrderResponse expected = new OrderResponse();
		when(completionCommand.complete(any())).thenReturn(expected);

		assertSame(
				expected,
				service.completeOrder(CUSTOMER_ID, orderPublicId));
	}

	@Test
	void translatesCompletionLockFailure() {
		UUID orderPublicId = UUID.randomUUID();
		when(completionCommand.complete(any()))
				.thenThrow(new CannotAcquireLockException("timeout"));

		assertThrows(
				OrderCompletionConflictException.class,
				() -> service.completeOrder(CUSTOMER_ID, orderPublicId));
	}

	@Test
	void failsClosedWhenReservationAllocationIsNotActivated() {
		OrderServiceImpl inactiveService = new OrderServiceImpl(
				new ReservationAllocationActivation(),
				conversionCommand,
				completionCommand,
				replayService,
				orderRepository,
				orderItemRepository,
				responseMapper);

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> inactiveService.createOrder(CUSTOMER_ID, request(), KEY));
	}

	@Test
	void verifiesWinnerThenRetriesAfterIdempotencyRace() {
		OrderResponse expected = new OrderResponse();
		when(conversionCommand.convert(any()))
				.thenThrow(new OrderIdempotencyRaceException(
						new RuntimeException("constraint")))
				.thenReturn(expected);

		assertSame(expected, service.createOrder(CUSTOMER_ID, request(), KEY));
		verify(conversionCommand, times(2)).convert(any());
		verify(replayService).verifyWinner(
				eq(CUSTOMER_ID),
				eq(KEY),
				anyString());
	}

	@Test
	void translatesLockFailureOnRecoveryRetryWithoutLooping() {
		when(conversionCommand.convert(any()))
				.thenThrow(new OrderIdempotencyRaceException(
						new RuntimeException("constraint")))
				.thenThrow(new CannotAcquireLockException("timeout"));

		assertThrows(
				OrderConversionConflictException.class,
				() -> service.createOrder(CUSTOMER_ID, request(), KEY));
		verify(conversionCommand, times(2)).convert(any());
	}

	private OrderCreateRequest request() {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setReservationPublicId(RESERVATION_ID);
		return request;
	}
}
