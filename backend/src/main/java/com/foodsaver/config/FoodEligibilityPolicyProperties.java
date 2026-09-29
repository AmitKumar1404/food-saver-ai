package com.foodsaver.config;

import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "foodsaver.food-eligibility.policy")
@Validated
@Getter
@Setter
public class FoodEligibilityPolicyProperties {

	@NotNull(message = "Food eligibility policy effective-from is required")
	private Instant effectiveFrom;
}
