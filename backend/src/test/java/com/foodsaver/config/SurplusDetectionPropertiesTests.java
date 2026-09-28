package com.foodsaver.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class SurplusDetectionPropertiesTests {

	private final Validator validator =
			Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void acceptsMinimumThreshold() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("0.001"));

		assertTrue(validator.validate(properties).isEmpty());
	}

	@Test
	void acceptsConfiguredDefaultThreshold() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("5.000"));

		assertTrue(validator.validate(properties).isEmpty());
	}

	@Test
	void acceptsMaximumDecimalCapacity() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("999999999.999"));

		assertTrue(validator.validate(properties).isEmpty());
	}

	@Test
	void rejectsZeroThreshold() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(BigDecimal.ZERO);

		assertFalse(validator.validate(properties).isEmpty());
	}

	@Test
	void rejectsNegativeThreshold() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("-1.000"));

		assertFalse(validator.validate(properties).isEmpty());
	}

	@Test
	void rejectsThresholdWithMoreThanThreeDecimalPlaces() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("5.0001"));

		assertFalse(validator.validate(properties).isEmpty());
	}

	@Test
	void rejectsThresholdBeyondDecimalCapacity() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();
		properties.setThresholdQuantity(new BigDecimal("1000000000.000"));

		assertFalse(validator.validate(properties).isEmpty());
	}

	@Test
	void rejectsMissingThreshold() {
		SurplusDetectionProperties properties = new SurplusDetectionProperties();

		assertFalse(validator.validate(properties).isEmpty());
	}
}
