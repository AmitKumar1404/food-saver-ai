package com.foodsaver.dto.response;

import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FoodEligibilityRuleResultResponse {

	private String ruleCode;

	private FoodEligibilityRuleOutcome outcome;

	private FoodEligibilityReasonCode reasonCode;

	private String message;
}
