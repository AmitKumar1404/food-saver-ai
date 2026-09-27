package com.foodsaver.dto.request;

import java.math.BigDecimal;

import com.foodsaver.enums.ProductCategory;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ProductCreateRequest {

	@NotBlank(message = "Product name is required")
	@Size(max = 150, message = "Product name must not exceed 150 characters")
	private String name;

	@Size(max = 1000, message = "Description must not exceed 1000 characters")
	private String description;

	@NotNull(message = "Product category is required")
	private ProductCategory category;

	@NotNull(message = "Base price is required")
	@DecimalMin(
			value = "0.0",
			inclusive = false,
			message = "Base price must be greater than 0")
	private BigDecimal basePrice;

	@NotBlank(message = "Currency code is required")
	@Size(
			min = 3,
			max = 3,
			message = "Currency code must be exactly 3 characters")
	private String currencyCode;
}
