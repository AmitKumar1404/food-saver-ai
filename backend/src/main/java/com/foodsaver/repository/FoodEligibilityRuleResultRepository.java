package com.foodsaver.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.FoodEligibilityRuleResultEntity;

public interface FoodEligibilityRuleResultRepository
		extends JpaRepository<FoodEligibilityRuleResultEntity, Long> {
}
