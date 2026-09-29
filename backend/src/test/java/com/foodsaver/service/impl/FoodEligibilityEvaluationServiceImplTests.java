package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;
import com.foodsaver.eligibility.facts.EligibilityFactProvider;
import com.foodsaver.eligibility.facts.FoodEligibilityFacts;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicy;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicyResolver;
import com.foodsaver.eligibility.rule.DeterministicRuleEvaluator;
import com.foodsaver.eligibility.rule.FoodEligibilityEvaluationResult;
import com.foodsaver.eligibility.rule.FoodEligibilityRuleResult;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.FoodEligibilityRuleResultEntity;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.FoodEligibilityRuleResultRepository;
import com.foodsaver.repository.SurplusDetectionRepository;

@ExtendWith(MockitoExtension.class)
class FoodEligibilityEvaluationServiceImplTests {

	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID PRODUCT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID DETECTION_PUBLIC_ID = UUID.randomUUID();

	@Mock
	private EligibilityFactProvider factProvider;

	@Mock
	private FoodEligibilityPolicyResolver policyResolver;

	@Mock
	private DeterministicRuleEvaluator ruleEvaluator;

	@Mock
	private SurplusDetectionRepository surplusDetectionRepository;

	@Mock
	private FoodEligibilityEvaluationRepository evaluationRepository;

	@Mock
	private FoodEligibilityRuleResultRepository ruleResultRepository;

	private FoodEligibilityEvaluationServiceImpl service;
	private FoodEligibilityFacts facts;
	private FoodEligibilityPolicy policy;
	private Restaurant restaurant;
	private Product product;
	private Inventory inventory;
	private SurplusDetection detection;

	@BeforeEach
	void setUp() {
		service = new FoodEligibilityEvaluationServiceImpl(
				factProvider,
				policyResolver,
				ruleEvaluator,
				surplusDetectionRepository,
				evaluationRepository,
				ruleResultRepository);
		facts = validFacts();
		policy = new FoodEligibilityPolicy(
				"FOOD_ELIGIBILITY_V1",
				"1.0",
				"FOODSAVER_INTERNAL_V1_POLICY",
				configuredPolicyEffectiveFrom());
		createDomainChain();
	}

	@Test
	void persistsEligibleEvaluationSnapshotAndEveryRuleResult() {
		List<FoodEligibilityRuleResult> rules = List.of(
				rule("OWNERSHIP_CONSISTENCY", FoodEligibilityRuleOutcome.PASS, null),
				rule("POLICY_AVAILABILITY", FoodEligibilityRuleOutcome.PASS, null));
		arrangeEvaluation(FoodEligibilityStatus.ELIGIBLE_FOR_OFFER, rules);

		FoodEligibilityEvaluationResponse response = evaluate();

		assertEquals(
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				response.getStatus());
		assertEquals(2, response.getRuleResults().size());
		FoodEligibilityEvaluation evaluation = capturedEvaluation();
		assertSame(detection, evaluation.getSurplusDetection());
		assertEquals("FOOD_ELIGIBILITY_V1", evaluation.getPolicyKey());
		assertEquals("1.0", evaluation.getPolicyVersion());
		assertEquals(
				"FOODSAVER_INTERNAL_V1_POLICY",
				evaluation.getPolicySourceReference());
		assertEquals(3L, evaluation.getEvaluatedInventoryVersion());
		assertEquals(
				new BigDecimal("8.000"),
				evaluation.getEvaluatedAvailableQuantity());
		assertEquals(facts.evaluatedAt(), evaluation.getEvaluatedAt());

		ArgumentCaptor<List<FoodEligibilityRuleResultEntity>> captor =
				ruleEntityCaptor();
		verify(ruleResultRepository).saveAll(captor.capture());
		assertEquals(2, captor.getValue().size());
		assertTrue(captor.getValue().stream()
				.allMatch(ruleEntity ->
						ruleEntity.getEvaluation() == evaluation));
		FoodEligibilityRuleResultEntity firstRule = captor.getValue().getFirst();
		assertEquals("OWNERSHIP_CONSISTENCY", firstRule.getRuleCode());
		assertEquals(FoodEligibilityRuleOutcome.PASS, firstRule.getOutcome());
		assertEquals("Rule result", firstRule.getMessage());
	}

	@Test
	void persistsNotEligibleStatusForBlockResult() {
		arrangeEvaluation(
				FoodEligibilityStatus.NOT_ELIGIBLE,
				List.of(rule(
						"PRODUCT_LIFECYCLE",
						FoodEligibilityRuleOutcome.BLOCK,
						FoodEligibilityReasonCode.POLICY_RULE_BLOCKED)));

		evaluate();

		assertEquals(
				FoodEligibilityStatus.NOT_ELIGIBLE,
				capturedEvaluation().getStatus());
	}

	@Test
	void persistsRequiresReviewStatusForReviewResult() {
		arrangeEvaluation(
				FoodEligibilityStatus.REQUIRES_REVIEW,
				List.of(rule(
						"REQUIRED_FACTS",
						FoodEligibilityRuleOutcome.REVIEW,
						FoodEligibilityReasonCode.REQUIRED_FACT_MISSING)));

		evaluate();

		assertEquals(
				FoodEligibilityStatus.REQUIRES_REVIEW,
				capturedEvaluation().getStatus());
	}

	@Test
	void allowsMultipleEvaluationsForTheSameSurplusDetection() {
		arrangeEvaluation(
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				List.of(rule(
						"POLICY_AVAILABILITY",
						FoodEligibilityRuleOutcome.PASS,
						null)));

		evaluate();
		evaluate();

		verify(evaluationRepository, times(2))
				.save(any(FoodEligibilityEvaluation.class));
		verify(ruleResultRepository, times(2)).saveAll(any());
	}

	@Test
	void serviceHasTransactionBoundary() {
		assertTrue(FoodEligibilityEvaluationServiceImpl.class
				.isAnnotationPresent(Transactional.class));
	}

	@Test
	void doesNotMutateExistingDomainEntities() {
		arrangeEvaluation(
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				List.of(rule(
						"POLICY_AVAILABILITY",
						FoodEligibilityRuleOutcome.PASS,
						null)));
		BigDecimal availableQuantity = inventory.getAvailableQuantity();
		InventoryStatus inventoryStatus = inventory.getStatus();
		ProductStatus productStatus = product.getStatus();
		RestaurantStatus restaurantStatus = restaurant.getStatus();
		BigDecimal detectedQuantity = detection.getDetectedQuantity();
		SurplusDetectionStatus detectionStatus = detection.getStatus();

		evaluate();

		assertSame(availableQuantity, inventory.getAvailableQuantity());
		assertEquals(inventoryStatus, inventory.getStatus());
		assertEquals(productStatus, product.getStatus());
		assertEquals(restaurantStatus, restaurant.getStatus());
		assertSame(detectedQuantity, detection.getDetectedQuantity());
		assertEquals(detectionStatus, detection.getStatus());
	}

	private FoodEligibilityEvaluationResponse evaluate() {
		return service.evaluateAndPersist(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID);
	}

	private void arrangeEvaluation(
			FoodEligibilityStatus status,
			List<FoodEligibilityRuleResult> rules) {
		FoodEligibilityEvaluationResult result =
				new FoodEligibilityEvaluationResult(status, rules);
		when(factProvider.loadFacts(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				DETECTION_PUBLIC_ID)).thenReturn(facts);
		when(policyResolver.resolve(facts, facts.evaluatedAt()))
				.thenReturn(Optional.of(policy));
		when(ruleEvaluator.evaluate(facts, policy)).thenReturn(result);
		when(surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						DETECTION_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(detection));
		when(evaluationRepository.save(any(FoodEligibilityEvaluation.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private FoodEligibilityEvaluation capturedEvaluation() {
		ArgumentCaptor<FoodEligibilityEvaluation> captor =
				ArgumentCaptor.forClass(FoodEligibilityEvaluation.class);
		verify(evaluationRepository).save(captor.capture());
		return captor.getValue();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private ArgumentCaptor<List<FoodEligibilityRuleResultEntity>> ruleEntityCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
	}

	private FoodEligibilityRuleResult rule(
			String ruleCode,
			FoodEligibilityRuleOutcome outcome,
			FoodEligibilityReasonCode reasonCode) {
		return new FoodEligibilityRuleResult(
				ruleCode,
				outcome,
				reasonCode,
				"Rule result");
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
						DETECTION_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SurplusDetectionStatus.POTENTIAL_SURPLUS,
						new BigDecimal("8.000"),
						new BigDecimal("5.000"),
						Instant.parse("2026-09-29T06:00:00Z")),
				Instant.parse("2026-09-29T07:00:00Z"));
	}

	private void createDomainChain() {
		restaurant = new Restaurant();
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setStatus(RestaurantStatus.ACTIVE);

		product = new Product();
		product.setRestaurant(restaurant);
		product.setStatus(ProductStatus.ACTIVE);

		inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory.setAvailableQuantity(new BigDecimal("8.000"));
		org.springframework.test.util.ReflectionTestUtils.setField(
				inventory,
				"publicId",
				INVENTORY_PUBLIC_ID);

		detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		detection.setDetectedQuantity(new BigDecimal("8.000"));
	}

	private Instant configuredPolicyEffectiveFrom() {
		String configuredValue = ResourceBundle
				.getBundle("application-test")
				.getString("foodsaver.food-eligibility.policy.effective-from");
		return Instant.parse(configuredValue);
	}
}
