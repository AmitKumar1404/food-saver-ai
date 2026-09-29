package com.foodsaver.eligibility.policy;

import java.time.Instant;

public record FoodEligibilityPolicy(
		String policyKey,
		String policyVersion,
		String sourceReference,
		Instant effectiveFrom) {
}
