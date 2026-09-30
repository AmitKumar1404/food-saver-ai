package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.OrderStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderResponse {

	private UUID publicId;
	private UUID customerPublicId;
	private UUID restaurantPublicId;
	private OrderStatus status;
	private BigDecimal totalAmount;
	private String currencyCode;
	private Instant confirmedAt;
	private Instant completedAt;
	private Instant createdAt;
	private Instant updatedAt;
	private OrderItemResponse item;
}
