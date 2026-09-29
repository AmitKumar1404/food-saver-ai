package com.foodsaver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.foodsaver.eligibility.rule.DeterministicRuleEvaluator;

@Configuration
public class FoodEligibilityConfiguration {

	@Bean
	DeterministicRuleEvaluator deterministicRuleEvaluator() {
		return new DeterministicRuleEvaluator();
	}
}
