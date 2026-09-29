package com.foodsaver.eligibility.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.foodsaver.eligibility.facts.FoodEligibilityFacts;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicy;
import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;

class DeterministicRuleEvaluatorTests {

	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID PRODUCT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID SURPLUS_DETECTION_PUBLIC_ID = UUID.randomUUID();
	private static final Instant EVALUATED_AT =
			Instant.parse("2026-09-29T07:00:00Z");

	private final DeterministicRuleEvaluator evaluator =
			new DeterministicRuleEvaluator();

	@Test
	void returnsEligibleForOfferWhenAllRulesPass() {
		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(validFacts(), validPolicy());

		assertEquals(FoodEligibilityStatus.ELIGIBLE_FOR_OFFER, result.status());
		assertEquals(7, result.ruleResults().size());
		assertTrue(result.ruleResults().stream()
				.allMatch(rule ->
						rule.outcome() == FoodEligibilityRuleOutcome.PASS));
	}

	@Test
	void returnsNotEligibleWhenSurplusDetectionIsNotSurplus() {
		FoodEligibilityFacts facts = withSurplusStatus(
				validFacts(),
				SurplusDetectionStatus.NOT_SURPLUS);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"SURPLUS_DETECTION_ELIGIBILITY",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.SURPLUS_DETECTION_NOT_ELIGIBLE);
	}

	@Test
	void returnsNotEligibleForOwnershipMismatch() {
		FoodEligibilityFacts facts = validFacts();
		FoodEligibilityFacts.ProductFacts mismatchedProduct =
				new FoodEligibilityFacts.ProductFacts(
						facts.product().publicId(),
						UUID.randomUUID(),
						facts.product().category(),
						facts.product().status());
		facts = new FoodEligibilityFacts(
				facts.restaurant(),
				mismatchedProduct,
				facts.inventory(),
				facts.surplusDetection(),
				facts.evaluatedAt());

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"OWNERSHIP_CONSISTENCY",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.OWNERSHIP_MISMATCH);
	}

	@Test
	void returnsReviewWhenRequiredFactsAreMissing() {
		FoodEligibilityFacts facts = validFacts();
		facts = new FoodEligibilityFacts(
				facts.restaurant(),
				null,
				facts.inventory(),
				facts.surplusDetection(),
				facts.evaluatedAt());

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.REQUIRES_REVIEW, result.status());
		assertRule(
				result,
				"REQUIRED_FACTS",
				FoodEligibilityRuleOutcome.REVIEW,
				FoodEligibilityReasonCode.REQUIRED_FACT_MISSING);
	}

	@Test
	void returnsNotEligibleForInactiveProduct() {
		FoodEligibilityFacts facts = withProductStatus(
				validFacts(),
				ProductStatus.INACTIVE);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"PRODUCT_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.POLICY_RULE_BLOCKED);
	}

	@Test
	void returnsNotEligibleForInactiveInventory() {
		FoodEligibilityFacts facts = withInventoryStatus(
				validFacts(),
				InventoryStatus.CLOSED);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"INVENTORY_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.POLICY_RULE_BLOCKED);
	}

	@Test
	void returnsNotEligibleForInvalidRestaurantLifecycle() {
		FoodEligibilityFacts facts = withRestaurantStatus(
				validFacts(),
				RestaurantStatus.SUSPENDED);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"RESTAURANT_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.POLICY_RULE_BLOCKED);
	}

	@Test
	void returnsNotEligibleForPendingVerificationRestaurant() {
		FoodEligibilityFacts facts = withRestaurantStatus(
				validFacts(),
				RestaurantStatus.PENDING_VERIFICATION);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertRule(
				result,
				"RESTAURANT_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK,
				FoodEligibilityReasonCode.POLICY_RULE_BLOCKED);
	}

	@Test
	void returnsReviewWhenPolicyIsMissing() {
		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(validFacts(), null);

		assertEquals(FoodEligibilityStatus.REQUIRES_REVIEW, result.status());
		assertRule(
				result,
				"POLICY_AVAILABILITY",
				FoodEligibilityRuleOutcome.REVIEW,
				FoodEligibilityReasonCode.POLICY_NOT_APPLICABLE);
	}

	@Test
	void returnsAllRuleFailures() {
		FoodEligibilityFacts facts = validFacts();
		FoodEligibilityFacts.ProductFacts product =
				new FoodEligibilityFacts.ProductFacts(
						facts.product().publicId(),
						UUID.randomUUID(),
						facts.product().category(),
						ProductStatus.INACTIVE);
		FoodEligibilityFacts.InventoryFacts inventory =
				new FoodEligibilityFacts.InventoryFacts(
						facts.inventory().publicId(),
						facts.inventory().restaurantPublicId(),
						facts.inventory().productPublicId(),
						facts.inventory().inventoryDate(),
						InventoryStatus.CLOSED,
						facts.inventory().availableQuantity(),
						facts.inventory().version());
		facts = new FoodEligibilityFacts(
				facts.restaurant(),
				product,
				inventory,
				facts.surplusDetection(),
				facts.evaluatedAt());

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(7, result.ruleResults().size());
		assertRuleOutcome(
				result,
				"OWNERSHIP_CONSISTENCY",
				FoodEligibilityRuleOutcome.BLOCK);
		assertRuleOutcome(
				result,
				"PRODUCT_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK);
		assertRuleOutcome(
				result,
				"INVENTORY_LIFECYCLE",
				FoodEligibilityRuleOutcome.BLOCK);
	}

	@Test
	void blockTakesPrecedenceOverReview() {
		FoodEligibilityFacts facts = withProductStatus(
				validFacts(),
				ProductStatus.INACTIVE);

		FoodEligibilityEvaluationResult result = evaluator.evaluate(facts, null);

		assertEquals(FoodEligibilityStatus.NOT_ELIGIBLE, result.status());
		assertTrue(result.ruleResults().stream()
				.anyMatch(rule ->
						rule.outcome() == FoodEligibilityRuleOutcome.BLOCK));
		assertTrue(result.ruleResults().stream()
				.anyMatch(rule ->
						rule.outcome() == FoodEligibilityRuleOutcome.REVIEW));
	}

	@Test
	void reviewIsReturnedWhenThereAreNoBlocks() {
		FoodEligibilityPolicy policyWithoutVersion = new FoodEligibilityPolicy(
				"platform-v1",
				null,
				"approved-policy-source",
				Instant.parse("2026-09-01T00:00:00Z"));

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(validFacts(), policyWithoutVersion);

		assertEquals(FoodEligibilityStatus.REQUIRES_REVIEW, result.status());
		assertTrue(result.ruleResults().stream()
				.noneMatch(rule ->
						rule.outcome() == FoodEligibilityRuleOutcome.BLOCK));
		assertRule(
				result,
				"REQUIRED_FACTS",
				FoodEligibilityRuleOutcome.REVIEW,
				FoodEligibilityReasonCode.REQUIRED_FACT_MISSING);
	}

	@Test
	void passResultsArePreservedWhenAnotherRuleBlocks() {
		FoodEligibilityFacts facts = withSurplusStatus(
				validFacts(),
				SurplusDetectionStatus.NOT_SURPLUS);

		FoodEligibilityEvaluationResult result =
				evaluator.evaluate(facts, validPolicy());

		assertEquals(7, result.ruleResults().size());
		assertRuleOutcome(
				result,
				"OWNERSHIP_CONSISTENCY",
				FoodEligibilityRuleOutcome.PASS);
		assertRuleOutcome(
				result,
				"REQUIRED_FACTS",
				FoodEligibilityRuleOutcome.PASS);
		assertRuleOutcome(
				result,
				"POLICY_AVAILABILITY",
				FoodEligibilityRuleOutcome.PASS);
	}

	private FoodEligibilityFacts validFacts() {
		return new FoodEligibilityFacts(
				new FoodEligibilityFacts.RestaurantFacts(
						RESTAURANT_PUBLIC_ID,
						"RESTAURANT",
						"IN",
						RestaurantStatus.ACTIVE,
						"Asia/Kolkata"),
				new FoodEligibilityFacts.ProductFacts(
						PRODUCT_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID,
						ProductCategory.MAIN_COURSE,
						ProductStatus.ACTIVE),
				new FoodEligibilityFacts.InventoryFacts(
						INVENTORY_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID,
						PRODUCT_PUBLIC_ID,
						LocalDate.of(2026, 9, 29),
						InventoryStatus.ACTIVE,
						new BigDecimal("8.000"),
						3L),
				new FoodEligibilityFacts.SurplusDetectionFacts(
						SURPLUS_DETECTION_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SurplusDetectionStatus.POTENTIAL_SURPLUS,
						new BigDecimal("8.000"),
						new BigDecimal("5.000"),
						Instant.parse("2026-09-29T06:00:00Z")),
				EVALUATED_AT);
	}

	private FoodEligibilityPolicy validPolicy() {
		return new FoodEligibilityPolicy(
				"platform-v1",
				"1.0.0",
				"approved-policy-source",
				Instant.parse("2026-09-01T00:00:00Z"));
	}

	private FoodEligibilityFacts withRestaurantStatus(
			FoodEligibilityFacts facts,
			RestaurantStatus status) {
		FoodEligibilityFacts.RestaurantFacts restaurant =
				new FoodEligibilityFacts.RestaurantFacts(
						facts.restaurant().publicId(),
						facts.restaurant().businessType(),
						facts.restaurant().countryCode(),
						status,
						facts.restaurant().timezone());
		return new FoodEligibilityFacts(
				restaurant,
				facts.product(),
				facts.inventory(),
				facts.surplusDetection(),
				facts.evaluatedAt());
	}

	private FoodEligibilityFacts withProductStatus(
			FoodEligibilityFacts facts,
			ProductStatus status) {
		FoodEligibilityFacts.ProductFacts product =
				new FoodEligibilityFacts.ProductFacts(
						facts.product().publicId(),
						facts.product().restaurantPublicId(),
						facts.product().category(),
						status);
		return new FoodEligibilityFacts(
				facts.restaurant(),
				product,
				facts.inventory(),
				facts.surplusDetection(),
				facts.evaluatedAt());
	}

	private FoodEligibilityFacts withInventoryStatus(
			FoodEligibilityFacts facts,
			InventoryStatus status) {
		FoodEligibilityFacts.InventoryFacts inventory =
				new FoodEligibilityFacts.InventoryFacts(
						facts.inventory().publicId(),
						facts.inventory().restaurantPublicId(),
						facts.inventory().productPublicId(),
						facts.inventory().inventoryDate(),
						status,
						facts.inventory().availableQuantity(),
						facts.inventory().version());
		return new FoodEligibilityFacts(
				facts.restaurant(),
				facts.product(),
				inventory,
				facts.surplusDetection(),
				facts.evaluatedAt());
	}

	private FoodEligibilityFacts withSurplusStatus(
			FoodEligibilityFacts facts,
			SurplusDetectionStatus status) {
		FoodEligibilityFacts.SurplusDetectionFacts surplusDetection =
				new FoodEligibilityFacts.SurplusDetectionFacts(
						facts.surplusDetection().publicId(),
						facts.surplusDetection().inventoryPublicId(),
						status,
						facts.surplusDetection().detectedQuantity(),
						facts.surplusDetection().thresholdQuantity(),
						facts.surplusDetection().detectedAt());
		return new FoodEligibilityFacts(
				facts.restaurant(),
				facts.product(),
				facts.inventory(),
				surplusDetection,
				facts.evaluatedAt());
	}

	private void assertRule(
			FoodEligibilityEvaluationResult result,
			String ruleCode,
			FoodEligibilityRuleOutcome outcome,
			FoodEligibilityReasonCode reasonCode) {
		FoodEligibilityRuleResult ruleResult = findRule(result, ruleCode);
		assertEquals(outcome, ruleResult.outcome());
		assertEquals(reasonCode, ruleResult.reasonCode());
	}

	private void assertRuleOutcome(
			FoodEligibilityEvaluationResult result,
			String ruleCode,
			FoodEligibilityRuleOutcome outcome) {
		assertEquals(outcome, findRule(result, ruleCode).outcome());
	}

	private FoodEligibilityRuleResult findRule(
			FoodEligibilityEvaluationResult result,
			String ruleCode) {
		return result.ruleResults().stream()
				.filter(rule -> rule.ruleCode().equals(ruleCode))
				.findFirst()
				.orElseThrow();
	}
}
