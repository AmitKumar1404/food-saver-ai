package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import com.foodsaver.config.ReservationAllocationActivation;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyRaceException;
import com.foodsaver.exception.ReservationValidationException;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTests {

	private static final UUID CUSTOMER_PUBLIC_ID =
			UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OFFER_PUBLIC_ID =
			UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final String IDEMPOTENCY_KEY = "reservation-create-1";

	@Mock
	private ReservationAllocationCommand allocationCommand;

	@Mock
	private ReservationReplayService replayService;

	private ReservationAllocationActivation activation;
	private ReservationServiceImpl service;

	@BeforeEach
	void setUp() {
		activation = new ReservationAllocationActivation();
		activation.activate();
		service = new ReservationServiceImpl(
				activation,
				allocationCommand,
				replayService);
	}

	@Test
	void delegatesNormalizedRequestAndCanonicalHashToAllocationCommand()
			throws Exception {
		ReservationResponse expected = new ReservationResponse();
		when(allocationCommand.allocate(any())).thenReturn(expected);

		ReservationResponse actual = service.createReservation(
				CUSTOMER_PUBLIC_ID,
				request(new BigDecimal("1.0")),
				IDEMPOTENCY_KEY);

		assertEquals(expected, actual);
		ArgumentCaptor<ReservationAllocationCommand.ReservationAllocationRequest>
				captor = ArgumentCaptor.forClass(
						ReservationAllocationCommand.ReservationAllocationRequest.class);
		verify(allocationCommand).allocate(captor.capture());
		ReservationAllocationCommand.ReservationAllocationRequest allocation =
				captor.getValue();
		assertEquals(CUSTOMER_PUBLIC_ID, allocation.customerPublicId());
		assertEquals(OFFER_PUBLIC_ID, allocation.offerPublicId());
		assertEquals(new BigDecimal("1.000"), allocation.quantity());
		String canonical = CUSTOMER_PUBLIC_ID
				+ "\n"
				+ OFFER_PUBLIC_ID
				+ "\n1.000";
		assertEquals(
				HexFormat.of().formatHex(
						MessageDigest.getInstance("SHA-256").digest(
								canonical.getBytes(StandardCharsets.UTF_8))),
				allocation.requestHash());
	}

	@Test
	void rejectsRequestsWhenAllocationGateIsDisabled() {
		service = new ReservationServiceImpl(
				new ReservationAllocationActivation(),
				allocationCommand,
				replayService);

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(new BigDecimal("1.000")),
						IDEMPOTENCY_KEY));

		verify(allocationCommand, never()).allocate(any());
	}

	@Test
	void validatesRequestBeforeOpeningAllocationTransaction() {
		assertThrows(
				ReservationValidationException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(BigDecimal.ZERO),
						IDEMPOTENCY_KEY));
		assertThrows(
				ReservationValidationException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(new BigDecimal("1.0001")),
						IDEMPOTENCY_KEY));
		assertThrows(
				ReservationValidationException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(new BigDecimal("1000000000.000")),
						IDEMPOTENCY_KEY));

		verify(allocationCommand, never()).allocate(any());
	}

	@Test
	void recoversIdempotencyRaceOnlyAfterAllocationCommandExits() {
		ReservationResponse expected = new ReservationResponse();
		when(allocationCommand.allocate(any()))
				.thenThrow(new ReservationIdempotencyRaceException(
						new RuntimeException("constraint")))
				.thenReturn(expected);

		ReservationResponse actual = service.createReservation(
				CUSTOMER_PUBLIC_ID,
				request(new BigDecimal("1.000")),
				IDEMPOTENCY_KEY);

		assertEquals(expected, actual);
		verify(replayService).verifyWinner(
				CUSTOMER_PUBLIC_ID,
				IDEMPOTENCY_KEY,
				hashForOneUnit());
		verify(allocationCommand, org.mockito.Mockito.times(2)).allocate(any());
	}

	@Test
	void translatesKnownPessimisticLockFailureToAllocationConflict() {
		when(allocationCommand.allocate(any()))
				.thenThrow(new CannotAcquireLockException("timeout"));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(new BigDecimal("1.000")),
						IDEMPOTENCY_KEY));
	}

	@Test
	void translatesPessimisticLockFailureAfterIdempotencyRaceRecovery() {
		when(allocationCommand.allocate(any()))
				.thenThrow(new ReservationIdempotencyRaceException(
						new RuntimeException("constraint")))
				.thenThrow(new CannotAcquireLockException("timeout"));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request(new BigDecimal("1.000")),
						IDEMPOTENCY_KEY));

		verify(replayService).verifyWinner(
				CUSTOMER_PUBLIC_ID,
				IDEMPOTENCY_KEY,
				hashForOneUnit());
		verify(allocationCommand, org.mockito.Mockito.times(2)).allocate(any());
	}

	private ReservationCreateRequest request(BigDecimal quantity) {
		ReservationCreateRequest request = new ReservationCreateRequest();
		request.setOfferPublicId(OFFER_PUBLIC_ID);
		request.setQuantity(quantity);
		return request;
	}

	private String hashForOneUnit() {
		String canonical = CUSTOMER_PUBLIC_ID
				+ "\n"
				+ OFFER_PUBLIC_ID
				+ "\n1.000";
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(
							canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
