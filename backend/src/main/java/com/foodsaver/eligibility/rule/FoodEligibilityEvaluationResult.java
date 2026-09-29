package com.foodsaver.eligibility.rule;

import java.util.List;

import com.foodsaver.enums.FoodEligibilityStatus;

public record FoodEligibilityEvaluationResult(
		FoodEligibilityStatus status,
		List<FoodEligibilityRuleResult> ruleResults) {

	public FoodEligibilityEvaluationResult {
		ruleResults = List.copyOf(ruleResults);
	}
}
