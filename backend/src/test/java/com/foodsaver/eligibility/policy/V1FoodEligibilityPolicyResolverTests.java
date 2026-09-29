package com.foodsaver.eligibility.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.foodsaver.config.FoodEligibilityPolicyProperties;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(
		classes = V1FoodEligibilityPolicyResolverTests.PolicyTestConfiguration.class)
@TestPropertySource(locations = "classpath:application-test.properties")
class V1FoodEligibilityPolicyResolverTests {

	@Autowired
	private FoodEligibilityPolicyProperties properties;

	@Autowired
	private FoodEligibilityPolicyResolver resolver;

	@Test
	void resolvesConfiguredImmutableV1Policy() {
		FoodEligibilityPolicy policy = resolver.resolve(null, null).orElseThrow();

		assertEquals("FOOD_ELIGIBILITY_V1", policy.policyKey());
		assertEquals("1.0", policy.policyVersion());
		assertEquals("FOODSAVER_INTERNAL_V1_POLICY", policy.sourceReference());
		assertEquals(properties.getEffectiveFrom(), policy.effectiveFrom());
	}

	@Test
	void rejectsMissingEffectiveFromConfiguration() {
		new ApplicationContextRunner()
				.withUserConfiguration(PolicyTestConfiguration.class)
				.run(context -> assertTrue(context.getStartupFailure() != null));
	}

	@Test
	void rejectsInvalidEffectiveFromConfiguration() {
		new ApplicationContextRunner()
				.withUserConfiguration(PolicyTestConfiguration.class)
				.withPropertyValues(
						"foodsaver.food-eligibility.policy.effective-from=invalid")
				.run(context -> assertTrue(context.getStartupFailure() != null));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(FoodEligibilityPolicyProperties.class)
	@Import(V1FoodEligibilityPolicyResolver.class)
	static class PolicyTestConfiguration {
	}
}
