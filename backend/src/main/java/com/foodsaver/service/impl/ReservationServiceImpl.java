package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.foodsaver.config.ReservationAllocationActivation;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyRaceException;
import com.foodsaver.exception.ReservationValidationException;
import com.foodsaver.service.ReservationService;

@Service
public class ReservationServiceImpl implements ReservationService {

	private static final int QUANTITY_SCALE = 3;
	private static final int QUANTITY_INTEGER_DIGITS = 9;
	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final ReservationAllocationActivation allocationActivation;
	private final ReservationAllocationCommand allocationCommand;
	private final ReservationReplayService replayService;

	public ReservationServiceImpl(
			ReservationAllocationActivation allocationActivation,
			ReservationAllocationCommand allocationCommand,
			ReservationReplayService replayService) {
		this.allocationActivation = allocationActivation;
		this.allocationCommand = allocationCommand;
		this.replayService = replayService;
	}

	@Override
	public ReservationResponse createReservation(
			UUID customerPublicId,
			ReservationCreateRequest request,
			String idempotencyKey) {
		if (customerPublicId == null) {
			throw new ReservationValidationException("Customer public ID is required");
		}
		validateIdempotencyKey(idempotencyKey);

		BigDecimal quantity = validateAndNormalizeRequest(request);
		String requestHash = calculateRequestHash(
				customerPublicId,
				request.getOfferPublicId(),
				quantity);

		allocationActivation.requireActive();

		ReservationAllocationCommand.ReservationAllocationRequest allocationRequest =
				new ReservationAllocationCommand.ReservationAllocationRequest(
						customerPublicId,
						request.getOfferPublicId(),
						quantity,
						idempotencyKey,
						requestHash);
		try {
			return allocateWithLockTranslation(allocationRequest);
		} catch (ReservationIdempotencyRaceException exception) {
			replayService.verifyWinner(
					customerPublicId,
					idempotencyKey,
					requestHash);
			return allocateWithLockTranslation(allocationRequest);
		}
	}

	private ReservationResponse allocateWithLockTranslation(
			ReservationAllocationCommand.ReservationAllocationRequest request) {
		try {
			return allocationCommand.allocate(request);
		} catch (PessimisticLockingFailureException exception) {
			throw new ReservationAllocationConflictException(
					"Reservation allocation is temporarily unavailable; retry the request",
					exception);
		}
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ReservationValidationException("Idempotency key is required");
		}
		if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new ReservationValidationException(
					"Idempotency key must not exceed 100 characters");
		}
	}

	private BigDecimal validateAndNormalizeRequest(ReservationCreateRequest request) {
		if (request == null) {
			throw new ReservationValidationException("Reservation request is required");
		}
		if (request.getOfferPublicId() == null) {
			throw new ReservationValidationException("Offer public ID is required");
		}

		BigDecimal quantity = request.getQuantity();
		if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new ReservationValidationException(
					"Reservation quantity must be greater than zero");
		}
		if (quantity.scale() > QUANTITY_SCALE
				|| integerDigits(quantity) > QUANTITY_INTEGER_DIGITS) {
			throw new ReservationValidationException(
					"Reservation quantity must have up to 9 integer and 3 fractional digits");
		}
		return quantity.setScale(QUANTITY_SCALE);
	}

	private int integerDigits(BigDecimal value) {
		return Math.max(value.precision() - value.scale(), 0);
	}

	private String calculateRequestHash(
			UUID customerPublicId,
			UUID offerPublicId,
			BigDecimal normalizedQuantity) {
		String canonicalInput = customerPublicId
				+ "\n"
				+ offerPublicId
				+ "\n"
				+ normalizedQuantity.toPlainString();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
