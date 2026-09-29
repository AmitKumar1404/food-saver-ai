package com.foodsaver.eligibility.rule;

import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;

public record FoodEligibilityRuleResult(
		String ruleCode,
		FoodEligibilityRuleOutcome outcome,
		FoodEligibilityReasonCode reasonCode,
		String message) {
}
