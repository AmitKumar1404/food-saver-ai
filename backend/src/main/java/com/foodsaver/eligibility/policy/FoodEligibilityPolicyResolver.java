package com.foodsaver.eligibility.policy;

import java.time.Instant;
import java.util.Optional;

import com.foodsaver.eligibility.facts.FoodEligibilityFacts;

public interface FoodEligibilityPolicyResolver {

	Optional<FoodEligibilityPolicy> resolve(
			FoodEligibilityFacts facts,
			Instant evaluatedAt);
}
