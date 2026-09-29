package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.foodsaver.enums.FoodEligibilityStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FoodEligibilityEvaluationResponse {

	private UUID publicId;

	private UUID restaurantPublicId;

	private UUID inventoryPublicId;

	private UUID surplusDetectionPublicId;

	private String policyKey;

	private String policyVersion;

	private String policySourceReference;

	private FoodEligibilityStatus status;

	private Long evaluatedInventoryVersion;

	private BigDecimal evaluatedAvailableQuantity;

	private Instant evaluatedAt;

	private List<FoodEligibilityRuleResultResponse> ruleResults;

	private Instant createdAt;

	private Instant updatedAt;
}
