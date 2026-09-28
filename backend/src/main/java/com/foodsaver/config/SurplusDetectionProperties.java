package com.foodsaver.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "foodsaver.surplus-detection")
@Validated
@Getter
@Setter
public class SurplusDetectionProperties {

	@NotNull(message = "Surplus detection threshold quantity is required")
	@DecimalMin(
			value = "0.001",
			message = "Surplus detection threshold quantity must be greater than zero")
	@Digits(
			integer = 9,
			fraction = 3,
			message = "Surplus detection threshold quantity must have at most "
					+ "9 integer digits and 3 decimal places")
	private BigDecimal thresholdQuantity;
}
