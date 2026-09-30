package com.foodsaver.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.config.ReservationAllocationActivation;
import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.exception.OrderCompletionConflictException;
import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderIdempotencyRaceException;
import com.foodsaver.exception.OrderNotFoundException;
import com.foodsaver.exception.OrderValidationException;
import com.foodsaver.repository.OrderItemRepository;
import com.foodsaver.repository.OrderRepository;
import com.foodsaver.service.OrderService;

@Service
public class OrderServiceImpl implements OrderService {

	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final ReservationAllocationActivation allocationActivation;
	private final OrderConversionCommand conversionCommand;
	private final OrderCompletionCommand completionCommand;
	private final OrderReplayService replayService;
	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final OrderResponseMapper responseMapper;

	public OrderServiceImpl(
			ReservationAllocationActivation allocationActivation,
			OrderConversionCommand conversionCommand,
			OrderCompletionCommand completionCommand,
			OrderReplayService replayService,
			OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			OrderResponseMapper responseMapper) {
		this.allocationActivation = allocationActivation;
		this.conversionCommand = conversionCommand;
		this.completionCommand = completionCommand;
		this.replayService = replayService;
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.responseMapper = responseMapper;
	}

	@Override
	public OrderResponse createOrder(
			UUID customerPublicId,
			OrderCreateRequest request,
			String idempotencyKey) {
		if (customerPublicId == null) {
			throw new OrderValidationException("Customer public ID is required");
		}
		if (request == null || request.getReservationPublicId() == null) {
			throw new OrderValidationException(
					"Reservation public ID is required");
		}
		validateIdempotencyKey(idempotencyKey);
		allocationActivation.requireActive();

		String requestHash = calculateRequestHash(
				customerPublicId,
				request.getReservationPublicId());
		OrderConversionCommand.OrderConversionRequest conversionRequest =
				new OrderConversionCommand.OrderConversionRequest(
						customerPublicId,
						request.getReservationPublicId(),
						idempotencyKey,
						requestHash);
		try {
			return convertWithLockTranslation(conversionRequest);
		} catch (OrderIdempotencyRaceException exception) {
			replayService.verifyWinner(
					customerPublicId,
					idempotencyKey,
					requestHash);
			return convertWithLockTranslation(conversionRequest);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public OrderResponse getOrder(
			UUID customerPublicId,
			UUID orderPublicId) {
		if (customerPublicId == null || orderPublicId == null) {
			throw new OrderValidationException(
					"Customer and Order public IDs are required");
		}
		var order = orderRepository
				.findByPublicIdAndCustomerPublicId(
						orderPublicId,
						customerPublicId)
				.orElseThrow(() -> new OrderNotFoundException(orderPublicId));
		var item = orderItemRepository.findByOrderId(order.getId())
				.orElseThrow(() -> new OrderConversionConflictException(
						"Order persistence is incomplete"));
		return responseMapper.toResponse(order, item);
	}

	@Override
	public OrderResponse completeOrder(
			UUID customerPublicId,
			UUID orderPublicId) {
		if (customerPublicId == null || orderPublicId == null) {
			throw new OrderValidationException(
					"Customer and Order public IDs are required");
		}
		allocationActivation.requireActive();
		try {
			return completionCommand.complete(
					new OrderCompletionCommand.OrderCompletionRequest(
							customerPublicId,
							orderPublicId));
		} catch (PessimisticLockingFailureException exception) {
			throw new OrderCompletionConflictException(
					"Order completion is temporarily unavailable; retry the request",
					exception);
		}
	}

	private OrderResponse convertWithLockTranslation(
			OrderConversionCommand.OrderConversionRequest request) {
		try {
			return conversionCommand.convert(request);
		} catch (PessimisticLockingFailureException exception) {
			throw new OrderConversionConflictException(
					"Order conversion is temporarily unavailable; retry the request",
					exception);
		}
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new OrderValidationException("Idempotency key is required");
		}
		if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new OrderValidationException(
					"Idempotency key must not exceed 100 characters");
		}
	}

	private String calculateRequestHash(
			UUID customerPublicId,
			UUID reservationPublicId) {
		String canonicalInput = customerPublicId
				+ "\n"
				+ reservationPublicId;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
