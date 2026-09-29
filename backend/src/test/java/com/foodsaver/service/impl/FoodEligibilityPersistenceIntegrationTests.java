package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.foodsaver.dto.response.FoodEligibilityEvaluationResponse;
import com.foodsaver.eligibility.facts.EligibilityFactProvider;
import com.foodsaver.eligibility.policy.FoodEligibilityPolicyResolver;
import com.foodsaver.eligibility.rule.DeterministicRuleEvaluator;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.FoodEligibilityRuleResultEntity;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.FoodEligibilityRuleResultRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.FoodEligibilityEvaluationService;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
class FoodEligibilityPersistenceIntegrationTests {

	@Autowired
	private FoodEligibilityEvaluationService evaluationService;

	@Autowired
	private EligibilityFactProvider factProvider;

	@Autowired
	private FoodEligibilityPolicyResolver policyResolver;

	@Autowired
	private DeterministicRuleEvaluator ruleEvaluator;

	@Autowired
	private RestaurantRepository restaurantRepository;

	@Autowired
	private ProductRepository productRepository;

	@Autowired
	private InventoryRepository inventoryRepository;

	@Autowired
	private SurplusDetectionRepository surplusDetectionRepository;

	@Autowired
	private FoodEligibilityEvaluationRepository evaluationRepository;

	@Autowired
	private FoodEligibilityRuleResultRepository ruleResultRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	void persistsEvaluationAndRuleResultsWithActualJpaMappings() {
		DomainFixture fixture = createFixture();

		FoodEligibilityEvaluationResponse response =
				evaluationService.evaluateAndPersist(
						fixture.restaurant().getPublicId(),
						fixture.inventory().getPublicId(),
						fixture.detection().getPublicId());
		entityManager.flush();
		entityManager.clear();

		FoodEligibilityEvaluation evaluation = evaluationRepository.findAll()
				.stream()
				.filter(candidate ->
						candidate.getPublicId().equals(response.getPublicId()))
				.findFirst()
				.orElseThrow();
		List<FoodEligibilityRuleResultEntity> ruleResults =
				ruleResultRepository.findAll()
						.stream()
						.filter(ruleResult -> ruleResult.getEvaluation()
								.getId()
								.equals(evaluation.getId()))
						.toList();

		assertEquals(FoodEligibilityStatus.ELIGIBLE_FOR_OFFER, evaluation.getStatus());
		assertEquals("FOOD_ELIGIBILITY_V1", evaluation.getPolicyKey());
		assertEquals("1.0", evaluation.getPolicyVersion());
		assertEquals(
				"FOODSAVER_INTERNAL_V1_POLICY",
				evaluation.getPolicySourceReference());
		assertEquals(
				fixture.inventory().getVersion(),
				evaluation.getEvaluatedInventoryVersion());
		assertEquals(
				fixture.inventory().getAvailableQuantity(),
				evaluation.getEvaluatedAvailableQuantity());
		assertEquals(7, ruleResults.size());
		assertTrue(ruleResults.stream()
				.allMatch(ruleResult ->
						ruleResult.getEvaluation().getId().equals(evaluation.getId())));
		assertTrue(ruleResults.stream()
				.allMatch(ruleResult ->
						ruleResult.getOutcome()
								== FoodEligibilityRuleOutcome.PASS));
		assertNotNull(evaluation.getCreatedAt());
	}

	@Test
	void rollsBackEvaluationWhenRuleResultPersistenceFails() {
		DomainFixture fixture = createFixture();
		long evaluationCountBefore = evaluationRepository.count();
		long ruleResultCountBefore = ruleResultRepository.count();
		FoodEligibilityRuleResultRepository failingRuleResultRepository =
				failingRuleResultRepository();
		FoodEligibilityEvaluationService transactionalService =
				transactionalService(failingRuleResultRepository);

		try {
			assertThrows(
					IllegalStateException.class,
					() -> transactionalService.evaluateAndPersist(
							fixture.restaurant().getPublicId(),
							fixture.inventory().getPublicId(),
							fixture.detection().getPublicId()));

			assertEquals(evaluationCountBefore, evaluationRepository.count());
			assertEquals(ruleResultCountBefore, ruleResultRepository.count());
		} finally {
			deleteFixture(fixture);
		}
	}

	private FoodEligibilityRuleResultRepository failingRuleResultRepository() {
		return (FoodEligibilityRuleResultRepository) Proxy.newProxyInstance(
				FoodEligibilityRuleResultRepository.class.getClassLoader(),
				new Class<?>[] {FoodEligibilityRuleResultRepository.class},
				(proxy, method, arguments) -> {
					Object result = invokeRepositoryMethod(method, arguments);
					if ("saveAll".equals(method.getName())) {
						throw new IllegalStateException(
								"Controlled rule-result persistence failure");
					}
					return result;
				});
	}

	private Object invokeRepositoryMethod(Method method, Object[] arguments)
			throws Throwable {
		try {
			return method.invoke(ruleResultRepository, arguments);
		} catch (InvocationTargetException exception) {
			throw exception.getCause();
		}
	}

	private FoodEligibilityEvaluationService transactionalService(
			FoodEligibilityRuleResultRepository failingRuleResultRepository) {
		FoodEligibilityEvaluationServiceImpl target =
				new FoodEligibilityEvaluationServiceImpl(
						factProvider,
						policyResolver,
						ruleEvaluator,
						surplusDetectionRepository,
						evaluationRepository,
						failingRuleResultRepository);
		TransactionInterceptor transactionInterceptor =
				new TransactionInterceptor(
						transactionManager,
						new AnnotationTransactionAttributeSource());
		ProxyFactory proxyFactory = new ProxyFactory(target);
		proxyFactory.addAdvice(transactionInterceptor);
		return (FoodEligibilityEvaluationService) proxyFactory.getProxy();
	}

	private DomainFixture createFixture() {
		Restaurant restaurant = new Restaurant();
		restaurant.setName("Eligibility Integration Restaurant");
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setContactEmail(
				"eligibility-" + UUID.randomUUID() + "@example.com");
		restaurant.setContactPhone("+910000000000");
		restaurant.setAddressLine1("Integration Test Address");
		restaurant.setCity("Test City");
		restaurant.setStateProvince("Test State");
		restaurant.setPostalCode("000000");
		restaurant.setCountryCode("IN");
		restaurant.setTimezone("Asia/Kolkata");
		restaurant.setCurrencyCode("INR");
		restaurant.setStatus(RestaurantStatus.ACTIVE);
		restaurant = restaurantRepository.saveAndFlush(restaurant);

		Product product = new Product();
		product.setRestaurant(restaurant);
		product.setName("Eligibility Integration Product");
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setBasePrice(new BigDecimal("100.00"));
		product.setCurrencyCode("INR");
		product.setStatus(ProductStatus.ACTIVE);
		product = productRepository.saveAndFlush(product);

		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setPreparedQuantity(new BigDecimal("10.000"));
		inventory.setAvailableQuantity(new BigDecimal("8.000"));
		inventory.setReservedQuantity(BigDecimal.ZERO);
		inventory.setSoldQuantity(new BigDecimal("2.000"));
		inventory.setInventoryDate(LocalDate.now());
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory = inventoryRepository.saveAndFlush(inventory);

		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setDetectedQuantity(new BigDecimal("8.000"));
		detection.setThresholdQuantity(new BigDecimal("5.000"));
		detection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		detection = surplusDetectionRepository.saveAndFlush(detection);

		return new DomainFixture(restaurant, product, inventory, detection);
	}

	private void deleteFixture(DomainFixture fixture) {
		surplusDetectionRepository.deleteById(fixture.detection().getId());
		inventoryRepository.deleteById(fixture.inventory().getId());
		productRepository.deleteById(fixture.product().getId());
		restaurantRepository.deleteById(fixture.restaurant().getId());
	}

	private record DomainFixture(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			SurplusDetection detection) {
	}
}
