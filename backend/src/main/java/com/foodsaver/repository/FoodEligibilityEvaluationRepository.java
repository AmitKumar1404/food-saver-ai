package com.foodsaver.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.FoodEligibilityEvaluation;

public interface FoodEligibilityEvaluationRepository
		extends JpaRepository<FoodEligibilityEvaluation, Long> {
}
