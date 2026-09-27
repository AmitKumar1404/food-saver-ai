package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProductResponse {

	private UUID publicId;

	private UUID restaurantPublicId;

	private String name;

	private String description;

	private ProductCategory category;

	private BigDecimal basePrice;

	private String currencyCode;

	private ProductStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
