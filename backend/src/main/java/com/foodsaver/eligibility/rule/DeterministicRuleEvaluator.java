package com.foodsaver.eligibility.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.foodsaver.eligibility.facts.FoodEligibilityFacts;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicy;
import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;

public class DeterministicRuleEvaluator {

	private static final String OWNERSHIP_CONSISTENCY = "OWNERSHIP_CONSISTENCY";
	private static final String REQUIRED_FACTS = "REQUIRED_FACTS";
	private static final String SURPLUS_DETECTION_ELIGIBILITY =
			"SURPLUS_DETECTION_ELIGIBILITY";
	private static final String RESTAURANT_LIFECYCLE = "RESTAURANT_LIFECYCLE";
	private static final String PRODUCT_LIFECYCLE = "PRODUCT_LIFECYCLE";
	private static final String INVENTORY_LIFECYCLE = "INVENTORY_LIFECYCLE";
	private static final String POLICY_AVAILABILITY = "POLICY_AVAILABILITY";

	public FoodEligibilityEvaluationResult evaluate(
			FoodEligibilityFacts facts,
			FoodEligibilityPolicy policy) {
		List<FoodEligibilityRuleResult> ruleResults = new ArrayList<>();
		ruleResults.add(evaluateOwnershipConsistency(facts));
		ruleResults.add(evaluateRequiredFacts(facts, policy));
		ruleResults.add(evaluateSurplusDetectionEligibility(facts));
		ruleResults.add(evaluateRestaurantLifecycle(facts));
		ruleResults.add(evaluateProductLifecycle(facts));
		ruleResults.add(evaluateInventoryLifecycle(facts));
		ruleResults.add(evaluatePolicyAvailability(policy));

		return new FoodEligibilityEvaluationResult(
				determineStatus(ruleResults),
				ruleResults);
	}

	private FoodEligibilityRuleResult evaluateOwnershipConsistency(
			FoodEligibilityFacts facts) {
		if (facts == null
				|| facts.restaurant() == null
				|| facts.product() == null
				|| facts.inventory() == null
				|| facts.surplusDetection() == null) {
			return review(
					OWNERSHIP_CONSISTENCY,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Ownership consistency cannot be evaluated because facts are missing");
		}

		UUID restaurantPublicId = facts.restaurant().publicId();
		UUID productRestaurantPublicId = facts.product().restaurantPublicId();
		UUID inventoryRestaurantPublicId = facts.inventory().restaurantPublicId();
		UUID inventoryPublicId = facts.inventory().publicId();
		UUID detectionInventoryPublicId =
				facts.surplusDetection().inventoryPublicId();

		if (restaurantPublicId == null
				|| productRestaurantPublicId == null
				|| inventoryRestaurantPublicId == null
				|| inventoryPublicId == null
				|| detectionInventoryPublicId == null) {
			return review(
					OWNERSHIP_CONSISTENCY,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Ownership consistency cannot be evaluated because identifiers "
							+ "are missing");
		}

		boolean ownershipMatches =
				Objects.equals(restaurantPublicId, productRestaurantPublicId)
						&& Objects.equals(
								restaurantPublicId,
								inventoryRestaurantPublicId)
						&& Objects.equals(
								inventoryPublicId,
								detectionInventoryPublicId);

		return ownershipMatches
				? pass(OWNERSHIP_CONSISTENCY, "Ownership facts are consistent")
				: block(
						OWNERSHIP_CONSISTENCY,
						FoodEligibilityReasonCode.OWNERSHIP_MISMATCH,
						"Restaurant, Product, Inventory, and Surplus Detection "
								+ "ownership facts are inconsistent");
	}

	private FoodEligibilityRuleResult evaluateRequiredFacts(
			FoodEligibilityFacts facts,
			FoodEligibilityPolicy policy) {
		if (hasMissingRequiredFacts(facts, policy)) {
			return review(
					REQUIRED_FACTS,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"One or more required eligibility facts are missing");
		}

		return pass(REQUIRED_FACTS, "All required eligibility facts are present");
	}

	private FoodEligibilityRuleResult evaluateSurplusDetectionEligibility(
			FoodEligibilityFacts facts) {
		if (facts == null
				|| facts.surplusDetection() == null
				|| facts.surplusDetection().status() == null) {
			return review(
					SURPLUS_DETECTION_ELIGIBILITY,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Surplus Detection status is required");
		}

		return facts.surplusDetection().status()
						== SurplusDetectionStatus.POTENTIAL_SURPLUS
				? pass(
						SURPLUS_DETECTION_ELIGIBILITY,
						"Surplus Detection is eligible for platform rule evaluation")
				: block(
						SURPLUS_DETECTION_ELIGIBILITY,
						FoodEligibilityReasonCode.SURPLUS_DETECTION_NOT_ELIGIBLE,
						"Surplus Detection is not POTENTIAL_SURPLUS");
	}

	private FoodEligibilityRuleResult evaluateRestaurantLifecycle(
			FoodEligibilityFacts facts) {
		if (facts == null
				|| facts.restaurant() == null
				|| facts.restaurant().status() == null) {
			return review(
					RESTAURANT_LIFECYCLE,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Restaurant status is required");
		}

		return facts.restaurant().status() == RestaurantStatus.ACTIVE
				? pass(RESTAURANT_LIFECYCLE, "Restaurant is active")
				: block(
						RESTAURANT_LIFECYCLE,
						FoodEligibilityReasonCode.POLICY_RULE_BLOCKED,
						"Restaurant lifecycle status blocks this platform workflow");
	}

	private FoodEligibilityRuleResult evaluateProductLifecycle(
			FoodEligibilityFacts facts) {
		if (facts == null
				|| facts.product() == null
				|| facts.product().status() == null) {
			return review(
					PRODUCT_LIFECYCLE,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Product status is required");
		}

		return facts.product().status() == ProductStatus.ACTIVE
				? pass(PRODUCT_LIFECYCLE, "Product is active")
				: block(
						PRODUCT_LIFECYCLE,
						FoodEligibilityReasonCode.POLICY_RULE_BLOCKED,
						"Product lifecycle status blocks this platform workflow");
	}

	private FoodEligibilityRuleResult evaluateInventoryLifecycle(
			FoodEligibilityFacts facts) {
		if (facts == null
				|| facts.inventory() == null
				|| facts.inventory().status() == null) {
			return review(
					INVENTORY_LIFECYCLE,
					FoodEligibilityReasonCode.REQUIRED_FACT_MISSING,
					"Inventory status is required");
		}

		return facts.inventory().status() == InventoryStatus.ACTIVE
				? pass(INVENTORY_LIFECYCLE, "Inventory is active")
				: block(
						INVENTORY_LIFECYCLE,
						FoodEligibilityReasonCode.POLICY_RULE_BLOCKED,
						"Inventory lifecycle status blocks this platform workflow");
	}

	private FoodEligibilityRuleResult evaluatePolicyAvailability(
			FoodEligibilityPolicy policy) {
		if (policy == null) {
			return review(
					POLICY_AVAILABILITY,
					FoodEligibilityReasonCode.POLICY_NOT_APPLICABLE,
					"No approved eligibility policy was supplied");
		}

		return pass(POLICY_AVAILABILITY, "An eligibility policy was supplied");
	}

	private boolean hasMissingRequiredFacts(
			FoodEligibilityFacts facts,
			FoodEligibilityPolicy policy) {
		if (facts == null
				|| facts.restaurant() == null
				|| facts.product() == null
				|| facts.inventory() == null
				|| facts.surplusDetection() == null
				|| facts.evaluatedAt() == null
				|| policy == null) {
			return true;
		}

		return facts.restaurant().publicId() == null
				|| isBlank(facts.restaurant().businessType())
				|| isBlank(facts.restaurant().countryCode())
				|| facts.restaurant().status() == null
				|| isBlank(facts.restaurant().timezone())
				|| facts.product().publicId() == null
				|| facts.product().restaurantPublicId() == null
				|| facts.product().category() == null
				|| facts.product().status() == null
				|| facts.inventory().publicId() == null
				|| facts.inventory().restaurantPublicId() == null
				|| facts.inventory().productPublicId() == null
				|| facts.inventory().inventoryDate() == null
				|| facts.inventory().status() == null
				|| facts.inventory().availableQuantity() == null
				|| facts.inventory().version() == null
				|| facts.surplusDetection().publicId() == null
				|| facts.surplusDetection().inventoryPublicId() == null
				|| facts.surplusDetection().status() == null
				|| facts.surplusDetection().detectedQuantity() == null
				|| facts.surplusDetection().thresholdQuantity() == null
				|| facts.surplusDetection().detectedAt() == null
				|| isBlank(policy.policyKey())
				|| isBlank(policy.policyVersion())
				|| isBlank(policy.sourceReference());
	}

	private FoodEligibilityStatus determineStatus(
			List<FoodEligibilityRuleResult> ruleResults) {
		boolean hasBlock = ruleResults.stream()
				.anyMatch(result ->
						result.outcome() == FoodEligibilityRuleOutcome.BLOCK);
		if (hasBlock) {
			return FoodEligibilityStatus.NOT_ELIGIBLE;
		}

		boolean hasReview = ruleResults.stream()
				.anyMatch(result ->
						result.outcome() == FoodEligibilityRuleOutcome.REVIEW);
		return hasReview
				? FoodEligibilityStatus.REQUIRES_REVIEW
				: FoodEligibilityStatus.ELIGIBLE_FOR_OFFER;
	}

	private FoodEligibilityRuleResult pass(String ruleCode, String message) {
		return new FoodEligibilityRuleResult(
				ruleCode,
				FoodEligibilityRuleOutcome.PASS,
				null,
				message);
	}

	private FoodEligibilityRuleResult block(
			String ruleCode,
			FoodEligibilityReasonCode reasonCode,
			String message) {
		return new FoodEligibilityRuleResult(
				ruleCode,
				FoodEligibilityRuleOutcome.BLOCK,
				reasonCode,
				message);
	}

	private FoodEligibilityRuleResult review(
			String ruleCode,
			FoodEligibilityReasonCode reasonCode,
			String message) {
		return new FoodEligibilityRuleResult(
				ruleCode,
				FoodEligibilityRuleOutcome.REVIEW,
				reasonCode,
				message);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
