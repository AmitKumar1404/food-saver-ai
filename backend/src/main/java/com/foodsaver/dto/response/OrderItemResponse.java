package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderItemResponse {

	private UUID publicId;
	private UUID reservationPublicId;
	private UUID offerPublicId;
	private UUID productPublicId;
	private UUID inventoryPublicId;
	private String productNameSnapshot;
	private BigDecimal quantity;
	private BigDecimal unitPrice;
	private BigDecimal totalAmount;
	private String currencyCode;
	private Instant createdAt;
}
