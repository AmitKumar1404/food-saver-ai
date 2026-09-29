package com.foodsaver.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;
import com.foodsaver.dto.response.FoodEligibilityRuleResultResponse;
import com.foodsaver.eligibility.facts.EligibilityFactProvider;
import com.foodsaver.eligibility.facts.FoodEligibilityFacts;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicy;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicyResolver;
import com.foodsaver.eligibility.rule.DeterministicRuleEvaluator;
import com.foodsaver.eligibility.rule.FoodEligibilityEvaluationResult;
import com.foodsaver.eligibility.rule.FoodEligibilityRuleResult;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.FoodEligibilityRuleResultEntity;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.exception.SurplusDetectionNotFoundException;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.FoodEligibilityRuleResultRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.FoodEligibilityEvaluationService;

@Service
@Transactional
public class FoodEligibilityEvaluationServiceImpl
		implements FoodEligibilityEvaluationService {

	private final EligibilityFactProvider factProvider;
	private final FoodEligibilityPolicyResolver policyResolver;
	private final DeterministicRuleEvaluator ruleEvaluator;
	private final SurplusDetectionRepository surplusDetectionRepository;
	private final FoodEligibilityEvaluationRepository evaluationRepository;
	private final FoodEligibilityRuleResultRepository ruleResultRepository;

	public FoodEligibilityEvaluationServiceImpl(
			EligibilityFactProvider factProvider,
			FoodEligibilityPolicyResolver policyResolver,
			DeterministicRuleEvaluator ruleEvaluator,
			SurplusDetectionRepository surplusDetectionRepository,
			FoodEligibilityEvaluationRepository evaluationRepository,
			FoodEligibilityRuleResultRepository ruleResultRepository) {
		this.factProvider = factProvider;
		this.policyResolver = policyResolver;
		this.ruleEvaluator = ruleEvaluator;
		this.surplusDetectionRepository = surplusDetectionRepository;
		this.evaluationRepository = evaluationRepository;
		this.ruleResultRepository = ruleResultRepository;
	}

	@Override
	public FoodEligibilityEvaluationResponse evaluateAndPersist(
			UUID restaurantPublicId,
			UUID inventoryPublicId,
			UUID surplusDetectionPublicId) {
		FoodEligibilityFacts facts = factProvider.loadFacts(
				restaurantPublicId,
				inventoryPublicId,
				surplusDetectionPublicId);
		FoodEligibilityPolicy policy = policyResolver
				.resolve(facts, facts.evaluatedAt())
				.orElseThrow(() -> new IllegalStateException(
						"Approved V1 food eligibility policy is unavailable"));
		FoodEligibilityEvaluationResult result =
				ruleEvaluator.evaluate(facts, policy);
		SurplusDetection surplusDetection = surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						surplusDetectionPublicId,
						restaurantPublicId)
				.filter(detection -> detection.getInventory()
						.getPublicId()
						.equals(inventoryPublicId))
				.orElseThrow(() -> new SurplusDetectionNotFoundException(
						"Surplus detection not found: " + surplusDetectionPublicId
								+ " for inventory: " + inventoryPublicId
								+ " and restaurant: " + restaurantPublicId));

		FoodEligibilityEvaluation evaluation =
				evaluationRepository.save(new FoodEligibilityEvaluation(
						surplusDetection,
						policy.policyKey(),
						policy.policyVersion(),
						policy.sourceReference(),
						result.status(),
						facts.inventory().version(),
						facts.inventory().availableQuantity(),
						facts.evaluatedAt()));

		List<FoodEligibilityRuleResultEntity> ruleResultEntities =
				result.ruleResults().stream()
						.map(ruleResult -> new FoodEligibilityRuleResultEntity(
								evaluation,
								ruleResult.ruleCode(),
								ruleResult.outcome(),
								ruleResult.reasonCode(),
								ruleResult.message()))
						.toList();
		ruleResultRepository.saveAll(ruleResultEntities);

		return toResponse(
				evaluation,
				result,
				restaurantPublicId,
				inventoryPublicId,
				surplusDetectionPublicId);
	}

	private FoodEligibilityEvaluationResponse toResponse(
			FoodEligibilityEvaluation evaluation,
			FoodEligibilityEvaluationResult result,
			UUID restaurantPublicId,
			UUID inventoryPublicId,
			UUID surplusDetectionPublicId) {
		FoodEligibilityEvaluationResponse response =
				new FoodEligibilityEvaluationResponse();
		response.setPublicId(evaluation.getPublicId());
		response.setRestaurantPublicId(restaurantPublicId);
		response.setInventoryPublicId(inventoryPublicId);
		response.setSurplusDetectionPublicId(surplusDetectionPublicId);
		response.setPolicyKey(evaluation.getPolicyKey());
		response.setPolicyVersion(evaluation.getPolicyVersion());
		response.setPolicySourceReference(evaluation.getPolicySourceReference());
		response.setStatus(evaluation.getStatus());
		response.setEvaluatedInventoryVersion(
				evaluation.getEvaluatedInventoryVersion());
		response.setEvaluatedAvailableQuantity(
				evaluation.getEvaluatedAvailableQuantity());
		response.setEvaluatedAt(evaluation.getEvaluatedAt());
		response.setRuleResults(result.ruleResults().stream()
				.map(this::toRuleResultResponse)
				.toList());
		response.setCreatedAt(evaluation.getCreatedAt());
		response.setUpdatedAt(evaluation.getUpdatedAt());
		return response;
	}

	private FoodEligibilityRuleResultResponse toRuleResultResponse(
			FoodEligibilityRuleResult ruleResult) {
		FoodEligibilityRuleResultResponse response =
				new FoodEligibilityRuleResultResponse();
		response.setRuleCode(ruleResult.ruleCode());
		response.setOutcome(ruleResult.outcome());
		response.setReasonCode(ruleResult.reasonCode());
		response.setMessage(ruleResult.message());
		return response;
	}
}
