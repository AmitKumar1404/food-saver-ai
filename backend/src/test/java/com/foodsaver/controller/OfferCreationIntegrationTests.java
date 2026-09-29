package com.foodsaver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import com.foodsaver.dto.request.OfferCreateRequest;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.OfferAlreadyExistsException;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.OfferService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OfferCreationIntegrationTests {

	private static final String ENDPOINT =
			"/api/v1/restaurants/{restaurantPublicId}/offers";
	private static final BigDecimal AVAILABLE_QUANTITY = new BigDecimal("8.000");
	private static final BigDecimal OFFERED_QUANTITY = new BigDecimal("2.000");
	private static final BigDecimal DISCOUNT_PERCENTAGE = new BigDecimal("20.00");
	private static final String INVENTORY_LOCK_WAIT_QUERY = """
			select count(*)
			from performance_schema.data_lock_waits waits
			join performance_schema.data_locks requested
			  on requested.engine_lock_id = waits.requesting_engine_lock_id
			join performance_schema.threads threads
			  on threads.thread_id = requested.thread_id
			where threads.processlist_id = ?
			  and requested.object_schema = database()
			  and requested.object_name = 'inventory'
			""";

	@Autowired
	private WebApplicationContext applicationContext;

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
	private OfferRepository offerRepository;

	@Autowired
	private OfferService offerService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.webAppContextSetup(applicationContext)
				.build();
	}

	@Test
	void createsOfferFromExplicitEligibleEvaluationWithoutMutatingUpstreamState()
			throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation selectedEvaluation = createEvaluation(
				fixture,
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				fixture.inventory().getVersion(),
				AVAILABLE_QUANTITY);
		FoodEligibilityEvaluation newerEvaluation = createEvaluation(
				fixture,
				FoodEligibilityStatus.NOT_ELIGIBLE,
				fixture.inventory().getVersion(),
				AVAILABLE_QUANTITY);

		entityManager.flush();
		entityManager.clear();

		InventoryState inventoryBefore = InventoryState.from(inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow());
		EvaluationState evaluationBefore = EvaluationState.from(evaluationRepository
				.findById(selectedEvaluation.getId())
				.orElseThrow());
		long offerCountBefore = offerRepository.count();
		entityManager.clear();

		postOffer(
				fixture.restaurant().getPublicId(),
				selectedEvaluation.getPublicId(),
				OFFERED_QUANTITY,
				Instant.now().plusSeconds(7200))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId").isNotEmpty())
				.andExpect(jsonPath("$.restaurantPublicId")
						.value(fixture.restaurant().getPublicId().toString()))
				.andExpect(jsonPath("$.productPublicId")
						.value(fixture.product().getPublicId().toString()))
				.andExpect(jsonPath("$.inventoryPublicId")
						.value(fixture.inventory().getPublicId().toString()))
				.andExpect(jsonPath("$.eligibilityEvaluationPublicId")
						.value(selectedEvaluation.getPublicId().toString()))
				.andExpect(jsonPath("$.originalPrice").value(100.0))
				.andExpect(jsonPath("$.discountPercentage").value(20.0))
				.andExpect(jsonPath("$.offerPrice").value(80.0))
				.andExpect(jsonPath("$.currencyCode").value("INR"))
				.andExpect(jsonPath("$.offeredQuantity").value(2.0))
				.andExpect(jsonPath("$.startAt").isNotEmpty())
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist())
				.andExpect(jsonPath("$.safetyStatus").doesNotExist())
				.andExpect(jsonPath("$.aiMetadata").doesNotExist());

		entityManager.flush();
		entityManager.clear();

		assertEquals(offerCountBefore + 1, offerRepository.count());
		Offer persistedOffer = offerRepository.findAll().stream()
				.filter(offer -> offer.getEligibilityEvaluation()
						.getId()
						.equals(selectedEvaluation.getId()))
				.findFirst()
				.orElseThrow();
		assertEquals(OfferStatus.ACTIVE, persistedOffer.getStatus());
		assertEquals(new BigDecimal("80.00"), persistedOffer.getOfferPrice());
		assertEquals(selectedEvaluation.getId(),
				persistedOffer.getEligibilityEvaluation().getId());
		assertFalse(offerRepository.existsByEligibilityEvaluationId(
				newerEvaluation.getId()));

		Inventory inventoryAfter = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		FoodEligibilityEvaluation evaluationAfter = evaluationRepository
				.findById(selectedEvaluation.getId())
				.orElseThrow();
		assertEquals(inventoryBefore, InventoryState.from(inventoryAfter));
		assertEquals(evaluationBefore, EvaluationState.from(evaluationAfter));
	}

	@Test
	void rejectsNotEligibleEvaluationWithConflict() throws Exception {
		assertEvaluationStatusConflict(FoodEligibilityStatus.NOT_ELIGIBLE);
	}

	@Test
	void rejectsRequiresReviewEvaluationWithConflict() throws Exception {
		assertEvaluationStatusConflict(FoodEligibilityStatus.REQUIRES_REVIEW);
	}

	@Test
	void rejectsStaleInventoryVersionWithConflict() throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEvaluation(
				fixture,
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				fixture.inventory().getVersion(),
				AVAILABLE_QUANTITY);
		fixture.inventory().setPreparedQuantity(new BigDecimal("11.000"));
		inventoryRepository.saveAndFlush(fixture.inventory());

		postValidOffer(fixture, evaluation)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("Food eligibility evaluation is stale for the current Inventory"));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(evaluation.getId()));
	}

	@Test
	void rejectsStaleAvailableQuantityWithConflict() throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEvaluation(
				fixture,
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				fixture.inventory().getVersion(),
				new BigDecimal("7.000"));

		postValidOffer(fixture, evaluation)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("Food eligibility evaluation is stale for the current Inventory"));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(evaluation.getId()));
	}

	@Test
	void rejectsQuantityAboveCurrentAndEvaluatedAvailabilityWithConflict()
			throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEligibleEvaluation(fixture);

		postOffer(
				fixture.restaurant().getPublicId(),
				evaluation.getPublicId(),
				new BigDecimal("9.000"),
				Instant.now().plusSeconds(7200))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("Offered quantity exceeds eligible Inventory availability"));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(evaluation.getId()));
	}

	@Test
	void rejectsDuplicateOfferForSameEvaluationWithConflict() throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEligibleEvaluation(fixture);

		postValidOffer(fixture, evaluation).andExpect(status().isCreated());
		postValidOffer(fixture, evaluation)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("An Offer already exists for the selected eligibility evaluation"));
	}

	@Test
	void rejectsAnotherOpenOfferForSameInventoryWithConflict() throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation firstEvaluation = createEligibleEvaluation(fixture);
		FoodEligibilityEvaluation secondEvaluation = createEligibleEvaluation(fixture);

		postValidOffer(fixture, firstEvaluation).andExpect(status().isCreated());
		postValidOffer(fixture, secondEvaluation)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("An open Offer already exists for the selected Inventory"));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(
				secondEvaluation.getId()));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentCreationUsesCurrentOfferStateAfterWaitingForInventoryLock()
			throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation firstEvaluation = createEligibleEvaluation(fixture);
		FoodEligibilityEvaluation secondEvaluation = createEligibleEvaluation(fixture);
		TransactionTemplate transactionTemplate =
				new TransactionTemplate(transactionManager);
		transactionTemplate.setIsolationLevel(
				TransactionDefinition.ISOLATION_REPEATABLE_READ);
		CountDownLatch inventoryLocked = new CountDownLatch(1);
		CountDownLatch secondSnapshotEstablished = new CountDownLatch(1);
		CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);
		AtomicLong secondConnectionId = new AtomicLong();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> firstCreation = null;
		Future<RuntimeException> secondCreation = null;
		Throwable testFailure = null;

		try {
			firstCreation = executor.submit(() ->
					transactionTemplate.executeWithoutResult(status -> {
						inventoryRepository.findByIdAndRestaurantId(
										fixture.inventory().getId(),
										fixture.restaurant().getId())
								.orElseThrow();
						inventoryLocked.countDown();
						await(allowFirstTransactionToCommit);
						offerService.createOffer(
								fixture.restaurant().getPublicId(),
								offerRequest(firstEvaluation));
					}));

			secondCreation = executor.submit(() -> {
				await(inventoryLocked);
				try {
					transactionTemplate.executeWithoutResult(status -> {
						restaurantRepository.findByPublicId(
										fixture.restaurant().getPublicId())
								.orElseThrow();
						secondConnectionId.set(currentMysqlConnectionId());
						secondSnapshotEstablished.countDown();
						offerService.createOffer(
								fixture.restaurant().getPublicId(),
								offerRequest(secondEvaluation));
					});
					throw new AssertionError(
							"Concurrent creation should have detected the open Offer");
				} catch (RuntimeException exception) {
					return exception;
				}
			});

			await(secondSnapshotEstablished);
			awaitInventoryLockWait(secondConnectionId.get());
			allowFirstTransactionToCommit.countDown();
			firstCreation.get(10, TimeUnit.SECONDS);
			RuntimeException conflict = secondCreation.get(10, TimeUnit.SECONDS);

			OfferAlreadyExistsException alreadyExists =
					assertInstanceOf(OfferAlreadyExistsException.class, conflict);
			assertEquals(
					"An open Offer already exists for the selected Inventory",
					alreadyExists.getMessage());

			long openOfferCount = transactionTemplate.execute(status ->
					offerRepository.findAll().stream()
							.filter(offer -> offer.getInventory().getId()
									.equals(fixture.inventory().getId()))
							.filter(offer -> offer.getStatus() == OfferStatus.ACTIVE)
							.filter(offer -> offer.getExpiresAt().isAfter(Instant.now()))
							.count());
			assertEquals(1L, openOfferCount);
			assertFalse(offerRepository.existsByEligibilityEvaluationId(
					secondEvaluation.getId()));
		} catch (Exception | AssertionError failure) {
			testFailure = failure;
			throw failure;
		} finally {
			allowFirstTransactionToCommit.countDown();
			shutdownExecutor(
					executor,
					testFailure,
					firstCreation,
					secondCreation);
			cleanupCommittedFixturePreservingFailure(
					transactionTemplate,
					fixture,
					List.of(firstEvaluation, secondEvaluation),
					testFailure);
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentCreationForSameEvaluationReturnsControlledDuplicateConflict()
			throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEligibleEvaluation(fixture);
		TransactionTemplate transactionTemplate =
				new TransactionTemplate(transactionManager);
		transactionTemplate.setIsolationLevel(
				TransactionDefinition.ISOLATION_REPEATABLE_READ);
		CountDownLatch inventoryLocked = new CountDownLatch(1);
		CountDownLatch secondSnapshotEstablished = new CountDownLatch(1);
		CountDownLatch allowFirstTransactionToCommit = new CountDownLatch(1);
		AtomicLong secondConnectionId = new AtomicLong();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> firstCreation = null;
		Future<RuntimeException> secondCreation = null;
		Throwable testFailure = null;

		try {
			firstCreation = executor.submit(() ->
					transactionTemplate.executeWithoutResult(status -> {
						inventoryRepository.findByIdAndRestaurantId(
										fixture.inventory().getId(),
										fixture.restaurant().getId())
								.orElseThrow();
						inventoryLocked.countDown();
						await(allowFirstTransactionToCommit);
						offerService.createOffer(
								fixture.restaurant().getPublicId(),
								offerRequest(evaluation));
					}));

			secondCreation = executor.submit(() -> {
				await(inventoryLocked);
				try {
					transactionTemplate.executeWithoutResult(status -> {
						restaurantRepository.findByPublicId(
										fixture.restaurant().getPublicId())
								.orElseThrow();
						secondConnectionId.set(currentMysqlConnectionId());
						secondSnapshotEstablished.countDown();
						offerService.createOffer(
								fixture.restaurant().getPublicId(),
								offerRequest(evaluation));
					});
					throw new AssertionError(
							"Concurrent creation should have detected the duplicate Offer");
				} catch (RuntimeException exception) {
					return exception;
				}
			});

			await(secondSnapshotEstablished);
			awaitInventoryLockWait(secondConnectionId.get());
			allowFirstTransactionToCommit.countDown();
			firstCreation.get(10, TimeUnit.SECONDS);
			RuntimeException conflict = secondCreation.get(10, TimeUnit.SECONDS);

			OfferAlreadyExistsException alreadyExists =
					assertInstanceOf(OfferAlreadyExistsException.class, conflict);
			assertEquals(
					"An Offer already exists for the selected eligibility evaluation",
					alreadyExists.getMessage());

			long evaluationOfferCount = transactionTemplate.execute(status ->
					offerRepository.findAll().stream()
							.filter(offer -> offer.getEligibilityEvaluation().getId()
									.equals(evaluation.getId()))
							.count());
			assertEquals(1L, evaluationOfferCount);
		} catch (Exception | AssertionError failure) {
			testFailure = failure;
			throw failure;
		} finally {
			allowFirstTransactionToCommit.countDown();
			shutdownExecutor(
					executor,
					testFailure,
					firstCreation,
					secondCreation);
			cleanupCommittedFixturePreservingFailure(
					transactionTemplate,
					fixture,
					List.of(evaluation),
					testFailure);
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void translatesMysqlEligibilityConstraintViolationAtServiceFlush() {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEligibleEvaluation(fixture);
		TransactionTemplate transactionTemplate =
				new TransactionTemplate(transactionManager);

		try {
			offerService.createOffer(
					fixture.restaurant().getPublicId(),
					offerRequest(evaluation));
			Object serviceTarget = AopTestUtils.getTargetObject(offerService);

			OfferAlreadyExistsException conflict = assertThrows(
					OfferAlreadyExistsException.class,
					() -> transactionTemplate.executeWithoutResult(status -> {
						Offer existing = offerRepository
								.findFirstByEligibilityEvaluationId(evaluation.getId())
								.orElseThrow();
						Offer duplicate = new Offer(
								existing.getRestaurant(),
								existing.getProduct(),
								existing.getInventory(),
								existing.getEligibilityEvaluation(),
								existing.getOriginalPrice(),
								existing.getDiscountPercentage(),
								existing.getOfferPrice(),
								existing.getCurrencyCode(),
								existing.getOfferedQuantity(),
								existing.getStartAt(),
								existing.getExpiresAt());
						duplicate.setStatus(OfferStatus.ACTIVE);

						ReflectionTestUtils.invokeMethod(
								serviceTarget,
								"saveOffer",
								duplicate);
					}));

			assertEquals(
					"An Offer already exists for the selected eligibility evaluation",
					conflict.getMessage());
			long evaluationOfferCount = transactionTemplate.execute(status ->
					offerRepository.findAll().stream()
							.filter(offer -> offer.getEligibilityEvaluation().getId()
									.equals(evaluation.getId()))
							.count());
			assertEquals(1L, evaluationOfferCount);
		} finally {
			cleanupCommittedFixture(
					transactionTemplate,
					fixture,
					List.of(evaluation));
		}
	}

	@Test
	void rejectsInvalidExpirationWithBadRequest() throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEligibleEvaluation(fixture);

		postOffer(
				fixture.restaurant().getPublicId(),
				evaluation.getPublicId(),
				OFFERED_QUANTITY,
				Instant.now().minusSeconds(1))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message")
						.value("Offer expiry must be after its marketplace start time"))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
		assertFalse(offerRepository.existsByEligibilityEvaluationId(evaluation.getId()));
	}

	@Test
	void rejectsMalformedRestaurantUuidWithBadRequest() throws Exception {
		mockMvc.perform(post(ENDPOINT, "not-a-uuid")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(
						UUID.randomUUID(),
						OFFERED_QUANTITY,
						Instant.now().plusSeconds(7200))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Invalid path parameter"))
				.andExpect(jsonPath("$.errors.restaurantPublicId[0]")
						.value("Must be a valid UUID"));
	}

	@Test
	void hidesEvaluationOwnedByAnotherRestaurantAsNotFound() throws Exception {
		DomainFixture requestedRestaurantFixture = createFixture();
		DomainFixture owningRestaurantFixture = createFixture();
		FoodEligibilityEvaluation foreignEvaluation =
				createEligibleEvaluation(owningRestaurantFixture);

		postOffer(
				requestedRestaurantFixture.restaurant().getPublicId(),
				foreignEvaluation.getPublicId(),
				OFFERED_QUANTITY,
				Instant.now().plusSeconds(7200))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.message")
						.value("Food eligibility evaluation not found: "
								+ foreignEvaluation.getPublicId()
								+ " for restaurant: "
								+ requestedRestaurantFixture.restaurant().getPublicId()));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(
				foreignEvaluation.getId()));
	}

	@Test
	void inventoryRepositoryRetainsPessimisticWriteLockContract()
			throws NoSuchMethodException {
		Lock lock = InventoryRepository.class
				.getMethod("findByIdAndRestaurantId", Long.class, Long.class)
				.getAnnotation(Lock.class);

		assertNotNull(lock);
		assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
	}

	private void assertEvaluationStatusConflict(FoodEligibilityStatus status)
			throws Exception {
		DomainFixture fixture = createFixture();
		FoodEligibilityEvaluation evaluation = createEvaluation(
				fixture,
				status,
				fixture.inventory().getVersion(),
				AVAILABLE_QUANTITY);

		postValidOffer(fixture, evaluation)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message")
						.value("Food eligibility evaluation does not permit Offer creation"));
		assertFalse(offerRepository.existsByEligibilityEvaluationId(evaluation.getId()));
	}

	private ResultActions postValidOffer(
			DomainFixture fixture,
			FoodEligibilityEvaluation evaluation) throws Exception {
		return postOffer(
				fixture.restaurant().getPublicId(),
				evaluation.getPublicId(),
				OFFERED_QUANTITY,
				Instant.now().plusSeconds(7200));
	}

	private OfferCreateRequest offerRequest(
			FoodEligibilityEvaluation evaluation) {
		OfferCreateRequest request = new OfferCreateRequest();
		request.setEligibilityEvaluationPublicId(evaluation.getPublicId());
		request.setOfferedQuantity(OFFERED_QUANTITY);
		request.setDiscountPercentage(DISCOUNT_PERCENTAGE);
		request.setExpiresAt(Instant.now().plusSeconds(7200));
		return request;
	}

	private ResultActions postOffer(
			UUID restaurantPublicId,
			UUID evaluationPublicId,
			BigDecimal offeredQuantity,
			Instant expiresAt) throws Exception {
		return mockMvc.perform(post(ENDPOINT, restaurantPublicId)
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestJson(
						evaluationPublicId,
						offeredQuantity,
						expiresAt)));
	}

	private String requestJson(
			UUID evaluationPublicId,
			BigDecimal offeredQuantity,
			Instant expiresAt) {
		return """
				{
				  "eligibilityEvaluationPublicId": "%s",
				  "offeredQuantity": %s,
				  "discountPercentage": %s,
				  "expiresAt": "%s"
				}
				""".formatted(
				evaluationPublicId,
				offeredQuantity,
				DISCOUNT_PERCENTAGE,
				expiresAt);
	}

	private FoodEligibilityEvaluation createEligibleEvaluation(
			DomainFixture fixture) {
		return createEvaluation(
				fixture,
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				fixture.inventory().getVersion(),
				AVAILABLE_QUANTITY);
	}

	private FoodEligibilityEvaluation createEvaluation(
			DomainFixture fixture,
			FoodEligibilityStatus status,
			Long evaluatedInventoryVersion,
			BigDecimal evaluatedAvailableQuantity) {
		return evaluationRepository.saveAndFlush(new FoodEligibilityEvaluation(
				fixture.detection(),
				"FOOD_ELIGIBILITY_V1",
				"1.0",
				"FOODSAVER_INTERNAL_V1_POLICY",
				status,
				evaluatedInventoryVersion,
				evaluatedAvailableQuantity,
				Instant.now()));
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException(
						"Timed out while coordinating concurrent Offer creation");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					"Interrupted while coordinating concurrent Offer creation",
					exception);
		}
	}

	private long currentMysqlConnectionId() {
		Long connectionId = jdbcTemplate.queryForObject(
				"select connection_id()",
				Long.class);
		if (connectionId == null) {
			throw new IllegalStateException("MySQL connection ID was not available");
		}
		return connectionId;
	}

	private void awaitInventoryLockWait(long connectionId) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waitCount = jdbcTemplate.queryForObject(
					INVENTORY_LOCK_WAIT_QUERY,
					Long.class,
					connectionId);
			if (waitCount != null && waitCount > 0) {
				return;
			}
			Thread.yield();
		}
		throw new AssertionError(
				"Transaction did not enter a MySQL Inventory lock wait");
	}

	private void shutdownExecutor(
			ExecutorService executor,
			Throwable testFailure,
			Future<?>... futures) {
		for (Future<?> future : futures) {
			if (future != null && !future.isDone()) {
				future.cancel(true);
			}
		}
		executor.shutdownNow();

		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				handleCleanupFailure(
						testFailure,
						new IllegalStateException(
								"Concurrent Offer test executor did not terminate"));
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			handleCleanupFailure(
					testFailure,
					new IllegalStateException(
							"Interrupted while stopping concurrent Offer test executor",
							exception));
		}
	}

	private void cleanupCommittedFixturePreservingFailure(
			TransactionTemplate transactionTemplate,
			DomainFixture fixture,
			List<FoodEligibilityEvaluation> evaluations,
			Throwable testFailure) {
		try {
			cleanupCommittedFixture(transactionTemplate, fixture, evaluations);
		} catch (RuntimeException cleanupFailure) {
			handleCleanupFailure(testFailure, cleanupFailure);
		}
	}

	private void handleCleanupFailure(
			Throwable testFailure,
			RuntimeException cleanupFailure) {
		if (testFailure != null) {
			testFailure.addSuppressed(cleanupFailure);
			return;
		}
		throw cleanupFailure;
	}

	private void cleanupCommittedFixture(
			TransactionTemplate transactionTemplate,
			DomainFixture fixture,
			List<FoodEligibilityEvaluation> evaluations) {
		transactionTemplate.executeWithoutResult(status -> {
			List<Offer> offers = offerRepository.findAll().stream()
					.filter(offer -> offer.getInventory().getId()
							.equals(fixture.inventory().getId()))
					.toList();
			offerRepository.deleteAll(offers);
			offerRepository.flush();
			evaluationRepository.deleteAll(evaluations);
			evaluationRepository.flush();
			surplusDetectionRepository.deleteById(fixture.detection().getId());
			surplusDetectionRepository.flush();
			inventoryRepository.deleteById(fixture.inventory().getId());
			inventoryRepository.flush();
			productRepository.deleteById(fixture.product().getId());
			productRepository.flush();
			restaurantRepository.deleteById(fixture.restaurant().getId());
			restaurantRepository.flush();
		});
	}

	private DomainFixture createFixture() {
		Restaurant restaurant = new Restaurant();
		restaurant.setName("Offer Integration Restaurant");
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setContactEmail("offer-" + UUID.randomUUID() + "@example.com");
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
		product.setName("Offer Integration Product");
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setBasePrice(new BigDecimal("100.00"));
		product.setCurrencyCode("INR");
		product.setStatus(ProductStatus.ACTIVE);
		product = productRepository.saveAndFlush(product);

		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setPreparedQuantity(new BigDecimal("10.000"));
		inventory.setAvailableQuantity(AVAILABLE_QUANTITY);
		inventory.setReservedQuantity(BigDecimal.ZERO.setScale(3));
		inventory.setSoldQuantity(new BigDecimal("2.000"));
		inventory.setInventoryDate(LocalDate.now());
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory = inventoryRepository.saveAndFlush(inventory);

		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setDetectedQuantity(AVAILABLE_QUANTITY);
		detection.setThresholdQuantity(new BigDecimal("5.000"));
		detection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		detection = surplusDetectionRepository.saveAndFlush(detection);

		return new DomainFixture(restaurant, product, inventory, detection);
	}

	private record DomainFixture(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			SurplusDetection detection) {
	}

	private record InventoryState(
			BigDecimal availableQuantity,
			BigDecimal reservedQuantity,
			BigDecimal soldQuantity,
			InventoryStatus status,
			Long version) {

		private static InventoryState from(Inventory inventory) {
			return new InventoryState(
					inventory.getAvailableQuantity(),
					inventory.getReservedQuantity(),
					inventory.getSoldQuantity(),
					inventory.getStatus(),
					inventory.getVersion());
		}
	}

	private record EvaluationState(
			FoodEligibilityStatus status,
			Long evaluatedInventoryVersion,
			BigDecimal evaluatedAvailableQuantity,
			Instant evaluatedAt,
			Instant createdAt,
			Instant updatedAt) {

		private static EvaluationState from(FoodEligibilityEvaluation evaluation) {
			return new EvaluationState(
					evaluation.getStatus(),
					evaluation.getEvaluatedInventoryVersion(),
					evaluation.getEvaluatedAvailableQuantity(),
					evaluation.getEvaluatedAt(),
					evaluation.getCreatedAt(),
					evaluation.getUpdatedAt());
		}
	}
}
