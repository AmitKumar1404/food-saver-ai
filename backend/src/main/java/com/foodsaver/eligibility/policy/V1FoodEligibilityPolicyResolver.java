package com.foodsaver.eligibility.policy;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.foodsaver.config.FoodEligibilityPolicyProperties;
import com.foodsaver.eligibility.facts.FoodEligibilityFacts;

@Component
public class V1FoodEligibilityPolicyResolver implements FoodEligibilityPolicyResolver {

	static final String POLICY_KEY = "FOOD_ELIGIBILITY_V1";
	static final String POLICY_VERSION = "1.0";
	static final String POLICY_SOURCE_REFERENCE = "FOODSAVER_INTERNAL_V1_POLICY";

	private final FoodEligibilityPolicy policy;

	public V1FoodEligibilityPolicyResolver(
			FoodEligibilityPolicyProperties properties) {
		this.policy = new FoodEligibilityPolicy(
				POLICY_KEY,
				POLICY_VERSION,
				POLICY_SOURCE_REFERENCE,
				properties.getEffectiveFrom());
	}

	@Override
	public Optional<FoodEligibilityPolicy> resolve(
			FoodEligibilityFacts facts,
			Instant evaluatedAt) {
		return Optional.of(policy);
	}
}
