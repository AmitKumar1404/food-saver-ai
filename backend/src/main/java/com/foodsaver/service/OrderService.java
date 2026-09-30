package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.response.OrderResponse;

public interface OrderService {

	OrderResponse createOrder(
			UUID customerPublicId,
			OrderCreateRequest request,
			String idempotencyKey);

	OrderResponse getOrder(UUID customerPublicId, UUID orderPublicId);
}
