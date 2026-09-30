package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.foodsaver.config.ReservationAllocationRelease;
import com.foodsaver.config.ReservationAllocationPreflight;
import com.foodsaver.config.ReservationAllocationPreflightObserver;
import com.foodsaver.config.ReservationProperties;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.OrderingReconciliationMarker;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.OrderingReconciliationMarkerRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.ReservationService;

@SpringBootTest(properties = "foodsaver.ordering.allocation-enabled=true")
@ActiveProfiles("test")
@Import(ReservationAllocationIntegrationTests.MarkerConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationAllocationIntegrationTests {

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

	private final List<Fixture> createdFixtures = new ArrayList<>();
	private final List<Offer> createdAdditionalOffers = new ArrayList<>();

	@Autowired
	private ReservationService reservationService;
	@Autowired
	private ReservationRepository reservationRepository;
	@Autowired
	private OfferRepository offerRepository;
	@Autowired
	private FoodEligibilityEvaluationRepository evaluationRepository;
	@Autowired
	private SurplusDetectionRepository detectionRepository;
	@Autowired
	private InventoryRepository inventoryRepository;
	@Autowired
	private ProductRepository productRepository;
	@Autowired
	private CustomerRepository customerRepository;
	@Autowired
	private RestaurantRepository restaurantRepository;
	@Autowired
	private OrderingReconciliationMarkerRepository markerRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TestTransactionObserver transactionObserver;
	@Autowired
	private TestPreflightObserver preflightObserver;
	@Autowired
	private ReservationAllocationPreflight preflight;
	@Autowired
	private ReservationProperties reservationProperties;
	@Autowired
	private TestSqlStatementInspector sqlStatementInspector;

	@AfterAll
	void cleanMarker() {
		markerRepository.deleteById(ReservationAllocationRelease.IDENTIFIER);
	}

	@AfterEach
	void cleanCreatedData() {
		transactionObserver.reset();
		preflightObserver.reset();
		sqlStatementInspector.clear();
		reservationProperties.setReservationTtl(java.time.Duration.ofMinutes(15));
		for (Fixture fixture : createdFixtures.reversed()) {
			List<Long> reservationIds = java.util.Arrays
					.stream(ReservationStatus.values())
					.flatMap(status -> reservationRepository
							.findAllocationTargetsByInventoryIdAndStatus(
									fixture.inventory().getId(),
									status)
							.stream())
					.map(target -> target.reservationId())
					.toList();
			reservationRepository.deleteAllById(reservationIds);
		}
		for (Offer offer : createdAdditionalOffers.reversed()) {
			offerRepository.deleteById(offer.getId());
			evaluationRepository.deleteById(
					offer.getEligibilityEvaluation().getId());
			detectionRepository.deleteById(
					offer.getEligibilityEvaluation()
							.getSurplusDetection().getId());
		}
		createdAdditionalOffers.clear();
		for (Fixture fixture : createdFixtures.reversed()) {
			offerRepository.deleteById(fixture.offer().getId());
			evaluationRepository.deleteById(
					fixture.offer().getEligibilityEvaluation().getId());
			detectionRepository.deleteById(
					fixture.offer().getEligibilityEvaluation()
							.getSurplusDetection().getId());
			inventoryRepository.deleteById(fixture.inventory().getId());
			productRepository.deleteById(fixture.product().getId());
			customerRepository.deleteAllById(
					fixture.customers().stream().map(Customer::getId).toList());
			restaurantRepository.deleteById(fixture.restaurant().getId());
		}
		createdFixtures.clear();
	}

	@Test
	void allocationMovesAvailableToReservedAndBalancesLedger() {
		Fixture fixture = createFixture(
				new BigDecimal("5.000"),
				new BigDecimal("5.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("5.000"),
				1);

		ReservationResponse response = reservationService.createReservation(
				fixture.customers().getFirst().getPublicId(),
				request(fixture.offer().getPublicId(), "2.000"),
				"integration-allocation-" + UUID.randomUUID());

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("3.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("2.000"), persistedInventory.getReservedQuantity());
		assertEquals(ReservationStatus.ACTIVE, response.getStatus());
		assertEquals(
				new BigDecimal("2.000"),
				reservationRepository.sumQuantityByInventoryIdAndStatusIn(
						fixture.inventory().getId(),
						List.of(
								ReservationStatus.ACTIVE,
								ReservationStatus.CONVERTED)));
	}

	@Test
	void aggregateMismatchFailsClosedWithoutPersistingReservation() {
		Fixture fixture = createFixture(
				new BigDecimal("5.000"),
				new BigDecimal("4.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("4.000"),
				1);

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> reservationService.createReservation(
						fixture.customers().getFirst().getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						"integration-mismatch-" + UUID.randomUUID()));

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("4.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
		assertTrue(reservationRepository
				.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE)
				.isEmpty());
	}

	@Test
	void twoCustomersCompetingForLastUnitAllocateExactlyOnce() throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("1.000"),
				2);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Result> first = executor.submit(() -> reserveAfterBarrier(
					start,
					fixture.customers().get(0),
					fixture.offer()));
			Future<Result> second = executor.submit(() -> reserveAfterBarrier(
					start,
					fixture.customers().get(1),
					fixture.offer()));
			List<Result> results = List.of(first.get(), second.get());

			assertEquals(1L, results.stream().filter(Result::created).count());
			assertEquals(1L, results.stream().filter(Result::conflict).count());
		}

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(BigDecimal.ZERO.setScale(3), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
		assertEquals(
				1,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	@Test
	void concurrentSameCustomerAndKeyReplaysOneAllocation() throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("2.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		Customer customer = fixture.customers().getFirst();
		String idempotencyKey = "same-key-" + UUID.randomUUID();
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ReservationResponse> first = executor.submit(() -> {
				start.await();
				return reservationService.createReservation(
						customer.getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						idempotencyKey);
			});
			Future<ReservationResponse> second = executor.submit(() -> {
				start.await();
				return reservationService.createReservation(
						customer.getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						idempotencyKey);
			});

			assertEquals(first.get().getPublicId(), second.get().getPublicId());
		}

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("1.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
		assertEquals(
				1,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	@Test
	void concurrentSameKeyWithDifferentHashAllocatesOnceAndConflictsOnce()
			throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("3.000"),
				new BigDecimal("3.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("3.000"),
				1);
		Customer customer = fixture.customers().getFirst();
		String idempotencyKey = "different-hash-" + UUID.randomUUID();
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Result> first = executor.submit(() -> reserveIdempotentAfterBarrier(
					start,
					customer,
					fixture.offer(),
					"1.000",
					idempotencyKey));
			Future<Result> second = executor.submit(() -> reserveIdempotentAfterBarrier(
					start,
					customer,
					fixture.offer(),
					"2.000",
					idempotencyKey));
			List<Result> results = List.of(first.get(), second.get());

			assertEquals(1L, results.stream().filter(Result::created).count());
			assertEquals(1L, results.stream().filter(Result::conflict).count());
		}

		assertEquals(
				1,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	@Test
	void concurrentCreatorsReleaseOneExpiredHoldExactlyOnce() throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("3.000"),
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("3.000"),
				2);
		Instant createdAt = Instant.now().minusSeconds(120);
		Reservation expiredHold = new Reservation(
				fixture.customers().getFirst(),
				fixture.restaurant(),
				fixture.offer(),
				fixture.inventory(),
				new BigDecimal("1.000"),
				new BigDecimal("80.00"),
				new BigDecimal("80.00"),
				"INR",
				createdAt.plusSeconds(60),
				"expired-hold-" + UUID.randomUUID(),
				"e".repeat(64));
		org.springframework.test.util.ReflectionTestUtils.setField(
				expiredHold,
				"createdAt",
				createdAt);
		expiredHold = reservationRepository.saveAndFlush(expiredHold);
		Long expiredReservationId = expiredHold.getId();
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Result> first = executor.submit(() -> reserveAfterBarrier(
					start,
					fixture.customers().get(0),
					fixture.offer()));
			Future<Result> second = executor.submit(() -> reserveAfterBarrier(
					start,
					fixture.customers().get(1),
					fixture.offer()));
			List<Result> results = List.of(first.get(), second.get());

			assertEquals(2L, results.stream().filter(Result::created).count());
		}

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("1.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("2.000"), persistedInventory.getReservedQuantity());
		Reservation persistedExpired = reservationRepository
				.findById(expiredReservationId)
				.orElseThrow();
		assertEquals(ReservationStatus.EXPIRED, persistedExpired.getStatus());
		assertEquals(
				2,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void inventoryLockWaitUsesIndependentReadCommittedConnectionsAndFreshState()
			throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("1.000"),
				2);
		String firstKey = "lock-holder-" + UUID.randomUUID();
		String waitingKey = "lock-waiter-" + UUID.randomUUID();
		TestTransactionObserver.TransactionBlock block =
				transactionObserver.blockAfterPersistence(firstKey);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ReservationResponse> first = executor.submit(() ->
					reservationService.createReservation(
							fixture.customers().get(0).getPublicId(),
							request(fixture.offer().getPublicId(), "1.000"),
							firstKey));
			await(block.entered());

			Future<RuntimeException> second = executor.submit(() -> {
				try {
					reservationService.createReservation(
							fixture.customers().get(1).getPublicId(),
							request(fixture.offer().getPublicId(), "1.000"),
							waitingKey);
					throw new AssertionError("Second allocation should conflict");
				} catch (RuntimeException exception) {
					return exception;
				}
			});

			long waitingConnection = transactionObserver.awaitConnection(waitingKey);
			awaitInventoryLockWait(waitingConnection);
			assertFalse(second.isDone());
			block.release().countDown();

			first.get(10, TimeUnit.SECONDS);
			assertTrue(second.get(10, TimeUnit.SECONDS)
					instanceof ReservationAllocationConflictException);
		}

		assertNotEquals(
				transactionObserver.connectionId(firstKey),
				transactionObserver.connectionId(waitingKey));
		assertEquals("READ-COMMITTED", transactionObserver.isolation(firstKey));
		assertEquals("READ-COMMITTED", transactionObserver.isolation(waitingKey));
		assertEquals(
				1,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void realIdempotencyConstraintRaceRollsBackThenRecoversWinner()
			throws Exception {
		runIdempotencyConstraintRace("1.000", "1.000", false);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void realIdempotencyConstraintRaceWithDifferentHashConflicts()
			throws Exception {
		runIdempotencyConstraintRace("2.000", "1.000", true);
	}

	@Test
	void failureAfterPersistenceRollsBackInventoryAndReservation() {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("2.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		String key = "rollback-after-persistence-" + UUID.randomUUID();
		transactionObserver.failAfterPersistence(key);

		assertThrows(
				TestTransactionFailure.class,
				() -> reservationService.createReservation(
						fixture.customers().getFirst().getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						key));

		Inventory persisted = inventoryRepository.findById(
				fixture.inventory().getId()).orElseThrow();
		assertEquals(new BigDecimal("2.000"), persisted.getAvailableQuantity());
		assertEquals(BigDecimal.ZERO.setScale(3), persisted.getReservedQuantity());
		assertTrue(reservationRepository
				.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE)
				.isEmpty());
	}

	@Test
	void expiredReplayFailureRollsBackReleaseAndRemainsActive() {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		String key = "expired-replay-rollback-" + UUID.randomUUID();
		Reservation expiredHold = createBackedReservation(
				fixture,
				fixture.customers().getFirst(),
				key,
				"1.000",
				Instant.now().minusSeconds(60));
		transactionObserver.failAfterExpiry(key);

		assertThrows(
				TestTransactionFailure.class,
				() -> reservationService.createReservation(
						fixture.customers().getFirst().getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						key));

		Reservation persistedReservation = reservationRepository
				.findById(expiredHold.getId())
				.orElseThrow();
		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(ReservationStatus.ACTIVE, persistedReservation.getStatus());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
	}

	@Test
	void concurrentExpiredSameKeyReplayReleasesExactlyOnce() throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		Customer customer = fixture.customers().getFirst();
		String key = "expired-concurrent-replay-" + UUID.randomUUID();
		Reservation expiredHold = createBackedReservation(
				fixture,
				customer,
				key,
				"1.000",
				Instant.now().minusSeconds(60));
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ReservationResponse> first = executor.submit(() -> {
				start.await();
				return reservationService.createReservation(
						customer.getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						key);
			});
			Future<ReservationResponse> second = executor.submit(() -> {
				start.await();
				return reservationService.createReservation(
						customer.getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						key);
			});

			assertEquals(ReservationStatus.EXPIRED, first.get().getStatus());
			assertEquals(ReservationStatus.EXPIRED, second.get().getStatus());
		}

		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("2.000"), persistedInventory.getAvailableQuantity());
		assertEquals(BigDecimal.ZERO.setScale(3), persistedInventory.getReservedQuantity());
		assertEquals(
				ReservationStatus.EXPIRED,
				reservationRepository.findById(expiredHold.getId())
						.orElseThrow()
						.getStatus());
	}

	@Test
	void subMicrosecondTtlRejectsWithoutExpiryOrInventoryMutation() {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				2);
		Reservation expiredHold = createBackedReservation(
				fixture,
				fixture.customers().getFirst(),
				"precision-existing-" + UUID.randomUUID(),
				"1.000",
				Instant.now().minusSeconds(60));
		reservationProperties.setReservationTtl(java.time.Duration.ofNanos(1));
		String rejectedKey = "precision-rejected-" + UUID.randomUUID();

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> reservationService.createReservation(
						fixture.customers().get(1).getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						rejectedKey));

		Reservation persistedExisting = reservationRepository
				.findById(expiredHold.getId())
				.orElseThrow();
		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(ReservationStatus.ACTIVE, persistedExisting.getStatus());
		assertEquals(null, persistedExisting.getExpiredAt());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
		assertTrue(reservationRepository
				.findByCustomerIdAndIdempotencyKey(
						fixture.customers().get(1).getId(),
						rejectedKey)
				.isEmpty());
	}

	@Test
	void microsecondTtlPersistsStrictlyOrderedTimestamps() {
		Fixture fixture = createFixture(
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("1.000"),
				1);
		reservationProperties.setReservationTtl(java.time.Duration.ofNanos(2_000));

		ReservationResponse response = reservationService.createReservation(
				fixture.customers().getFirst().getPublicId(),
				request(fixture.offer().getPublicId(), "1.000"),
				"precision-valid-" + UUID.randomUUID());
		Reservation persisted = reservationRepository
				.findByPublicId(response.getPublicId())
				.orElseThrow();

		assertTrue(persisted.getExpiresAt().isAfter(persisted.getCreatedAt()));
		assertEquals(
				java.time.Duration.ofNanos(2_000),
				java.time.Duration.between(
						persisted.getCreatedAt(),
						persisted.getExpiresAt()));
		assertEquals(persisted.getCreatedAt(), response.getCreatedAt());
		assertEquals(persisted.getUpdatedAt(), response.getUpdatedAt());
		assertEquals(persisted.getExpiresAt(), response.getExpiresAt());
	}

	@Test
	void expiredReplayResponseTimestampsMatchReloadedReservation() {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		Customer customer = fixture.customers().getFirst();
		String key = "expired-timestamp-replay-" + UUID.randomUUID();
		Reservation existing = createBackedReservation(
				fixture,
				customer,
				key,
				"1.000",
				Instant.now().minusSeconds(60));

		ReservationResponse response = reservationService.createReservation(
				customer.getPublicId(),
				request(fixture.offer().getPublicId(), "1.000"),
				key);
		Reservation persisted = reservationRepository
				.findById(existing.getId())
				.orElseThrow();

		assertEquals(ReservationStatus.EXPIRED, response.getStatus());
		assertEquals(persisted.getCreatedAt(), response.getCreatedAt());
		assertEquals(persisted.getUpdatedAt(), response.getUpdatedAt());
		assertEquals(persisted.getExpiresAt(), response.getExpiresAt());
		assertEquals(persisted.getExpiredAt(), persisted.getUpdatedAt());
		assertEquals(0, persisted.getExpiredAt().getNano() % 1_000);
	}

	@Test
	void failureAfterExpiryAllocationAndPersistenceRollsBackEveryMutation() {
		Fixture fixture = createFixture(
				new BigDecimal("3.000"),
				new BigDecimal("2.000"),
				new BigDecimal("1.000"),
				BigDecimal.ZERO,
				new BigDecimal("3.000"),
				2);
		Reservation expiredHold = createBackedReservation(
				fixture,
				fixture.customers().getFirst(),
				"combined-expired-" + UUID.randomUUID(),
				"1.000",
				Instant.now().minusSeconds(60));
		String newKey = "combined-rollback-" + UUID.randomUUID();
		transactionObserver.failAfterPersistence(newKey);

		assertThrows(
				TestTransactionFailure.class,
				() -> reservationService.createReservation(
						fixture.customers().get(1).getPublicId(),
						request(fixture.offer().getPublicId(), "1.000"),
						newKey));

		Reservation persistedExpired = reservationRepository
				.findById(expiredHold.getId())
				.orElseThrow();
		Inventory persistedInventory = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(ReservationStatus.ACTIVE, persistedExpired.getStatus());
		assertEquals(null, persistedExpired.getExpiredAt());
		assertEquals(new BigDecimal("1.000"), persistedExpired.getQuantity());
		assertEquals(new BigDecimal("3.000"), persistedInventory.getPreparedQuantity());
		assertEquals(new BigDecimal("2.000"), persistedInventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persistedInventory.getReservedQuantity());
		assertEquals(BigDecimal.ZERO.setScale(3), persistedInventory.getSoldQuantity());
		assertTrue(reservationRepository
				.findByCustomerIdAndIdempotencyKey(
						fixture.customers().get(1).getId(),
						newKey)
				.isEmpty());
		assertEquals(
				persistedInventory.getPreparedQuantity(),
				persistedInventory.getAvailableQuantity()
						.add(persistedInventory.getReservedQuantity())
						.add(persistedInventory.getSoldQuantity()));
		assertEquals(
				persistedInventory.getReservedQuantity(),
				reservationRepository.sumQuantityByInventoryIdAndStatusIn(
						fixture.inventory().getId(),
						List.of(
								ReservationStatus.ACTIVE,
								ReservationStatus.CONVERTED)));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void preflightRepeatableReadSnapshotDoesNotBlockAllocation() throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("2.000"),
				new BigDecimal("2.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("2.000"),
				1);
		TestPreflightObserver.PreflightBlock block =
				preflightObserver.blockAfterInventorySnapshotRead();
		String allocationKey = "preflight-consistency-" + UUID.randomUUID();

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> preflightResult = executor.submit(() -> preflight.run(null));
			await(block.entered());
			Future<ReservationResponse> allocation = executor.submit(() ->
					reservationService.createReservation(
							fixture.customers().getFirst().getPublicId(),
							request(fixture.offer().getPublicId(), "1.000"),
							allocationKey));
			allocation.get(10, TimeUnit.SECONDS);
			assertFalse(preflightResult.isDone());
			assertEquals("REPEATABLE-READ", preflightObserver.isolation());
			block.release().countDown();
			preflightResult.get(10, TimeUnit.SECONDS);
		}

		Inventory persisted = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("1.000"), persisted.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), persisted.getReservedQuantity());
		assertEquals(
				persisted.getReservedQuantity(),
				reservationRepository.sumQuantityByInventoryIdAndStatusIn(
						persisted.getId(),
						List.of(
								ReservationStatus.ACTIVE,
								ReservationStatus.CONVERTED)));
	}

	@Test
	@Transactional
	void generatedMySqlLockingQueriesOrderByInternalId() {
		Fixture fixture = createFixture(
				new BigDecimal("4.000"),
				new BigDecimal("2.000"),
				new BigDecimal("2.000"),
				BigDecimal.ZERO,
				new BigDecimal("4.000"),
				2);
		Offer secondOffer = createAdditionalOffer(
				fixture,
				new BigDecimal("4.000"));
		Reservation firstReservation = createBackedReservation(
				fixture,
				fixture.customers().get(0),
				secondOffer,
				"sql-order-first-" + UUID.randomUUID(),
				"1.000",
				Instant.now().plusSeconds(1800));
		Reservation secondReservation = createBackedReservation(
				fixture,
				fixture.customers().get(1),
				fixture.offer(),
				"sql-order-second-" + UUID.randomUUID(),
				"1.000",
				Instant.now().plusSeconds(1800));
		sqlStatementInspector.clear();

		offerRepository.findAllByIdInOrderByIdForAllocation(List.of(
				secondOffer.getId(),
				fixture.offer().getId()));
		reservationRepository.findAllByIdInOrderByIdForAllocation(List.of(
				secondReservation.getId(),
				firstReservation.getId()));

		String offerSql = sqlStatementInspector.lockingSelectFor("offers");
		String reservationSql =
				sqlStatementInspector.lockingSelectFor("reservations");
		assertOrderedById(offerSql, "offers");
		assertOrderedById(reservationSql, "reservations");
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentAllocationsLockCompleteMultiOfferReservationSetInIdOrder()
			throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("10.000"),
				new BigDecimal("6.000"),
				new BigDecimal("4.000"),
				BigDecimal.ZERO,
				new BigDecimal("10.000"),
				4);
		Offer secondOffer = createAdditionalOffer(
				fixture,
				new BigDecimal("10.000"));
		Reservation firstReservation = createBackedReservation(
				fixture,
				fixture.customers().get(0),
				secondOffer,
				"multi-lock-existing-high-offer-" + UUID.randomUUID(),
				"2.000",
				Instant.now().plusSeconds(1800));
		Reservation secondReservation = createBackedReservation(
				fixture,
				fixture.customers().get(1),
				fixture.offer(),
				"multi-lock-existing-low-offer-" + UUID.randomUUID(),
				"2.000",
				Instant.now().plusSeconds(1800));
		String firstKey = "multi-lock-first-" + UUID.randomUUID();
		String secondKey = "multi-lock-second-" + UUID.randomUUID();
		TestTransactionObserver.TransactionBlock block =
				transactionObserver.blockAfterPersistence(firstKey);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<ReservationResponse> first = executor.submit(() ->
					reservationService.createReservation(
							fixture.customers().get(2).getPublicId(),
							request(secondOffer.getPublicId(), "1.000"),
							firstKey));
			await(block.entered());
			Future<ReservationResponse> second = executor.submit(() ->
					reservationService.createReservation(
							fixture.customers().get(3).getPublicId(),
							request(fixture.offer().getPublicId(), "1.000"),
							secondKey));
			long waitingConnection = transactionObserver.awaitConnection(secondKey);
			awaitInventoryLockWait(waitingConnection);
			block.release().countDown();

			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}

		List<Long> expectedOfferIds = List.of(
				fixture.offer().getId(),
				secondOffer.getId()).stream().sorted().toList();
		List<Long> initialReservationIds = List.of(
				firstReservation.getId(),
				secondReservation.getId()).stream().sorted().toList();
		assertEquals(expectedOfferIds, transactionObserver.offerLocks(firstKey));
		assertEquals(
				initialReservationIds,
				transactionObserver.reservationLocks(firstKey));
		assertEquals(expectedOfferIds, transactionObserver.offerLocks(secondKey));
		List<Long> secondReservationLocks =
				transactionObserver.reservationLocks(secondKey);
		assertEquals(
				secondReservationLocks.stream().sorted().toList(),
				secondReservationLocks);
		assertEquals(3, secondReservationLocks.size());
		assertTrue(secondReservationLocks.containsAll(initialReservationIds));
		assertNotEquals(
				transactionObserver.connectionId(firstKey),
				transactionObserver.connectionId(secondKey));

		Inventory persisted = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(new BigDecimal("4.000"), persisted.getAvailableQuantity());
		assertEquals(new BigDecimal("6.000"), persisted.getReservedQuantity());
		assertEquals(
				persisted.getPreparedQuantity(),
				persisted.getAvailableQuantity()
						.add(persisted.getReservedQuantity())
						.add(persisted.getSoldQuantity()));
		assertEquals(
				persisted.getReservedQuantity(),
				reservationRepository.sumQuantityByInventoryIdAndStatusIn(
						persisted.getId(),
						List.of(
								ReservationStatus.ACTIVE,
								ReservationStatus.CONVERTED)));
	}

	private void assertOrderedById(String sql, String tableName) {
		assertNotNull(sql);
		String normalized = sql
				.replace("`", "")
				.replace("\"", "")
				.replaceAll("\\s+", " ")
				.toLowerCase();
		Matcher aliasMatcher = Pattern.compile(
				"\\bfrom " + tableName + " ([a-z0-9_]+)\\b")
				.matcher(normalized);
		assertTrue(aliasMatcher.find(), normalized);
		String alias = aliasMatcher.group(1);
		assertTrue(
				normalized.matches(
						".*\\border by " + Pattern.quote(alias)
								+ "\\.id(?: asc)?(?:\\s|,).*"),
				normalized);
		assertTrue(normalized.contains(" for update"), normalized);
	}

	private Result reserveAfterBarrier(
			CyclicBarrier start,
			Customer customer,
			Offer offer) throws Exception {
		start.await();
		try {
			reservationService.createReservation(
					customer.getPublicId(),
					request(offer.getPublicId(), "1.000"),
					"last-unit-" + customer.getPublicId());
			return new Result(true, false);
		} catch (ReservationAllocationConflictException exception) {
			assertTrue(exception.getMessage().contains("marketplace state"));
			return new Result(false, true);
		}
	}

	private Result reserveIdempotentAfterBarrier(
			CyclicBarrier start,
			Customer customer,
			Offer offer,
			String quantity,
			String idempotencyKey) throws Exception {
		start.await();
		try {
			reservationService.createReservation(
					customer.getPublicId(),
					request(offer.getPublicId(), quantity),
					idempotencyKey);
			return new Result(true, false);
		} catch (ReservationIdempotencyConflictException exception) {
			return new Result(false, true);
		}
	}

	private void runIdempotencyConstraintRace(
			String requestedQuantity,
			String winnerQuantity,
			boolean expectConflict) throws Exception {
		Fixture fixture = createFixture(
				new BigDecimal("4.000"),
				new BigDecimal("4.000"),
				BigDecimal.ZERO,
				BigDecimal.ZERO,
				new BigDecimal("4.000"),
				1);
		Customer customer = fixture.customers().getFirst();
		String key = "constraint-race-" + UUID.randomUUID();
		String winnerHash = requestHash(
				customer.getPublicId(),
				fixture.offer().getPublicId(),
				new BigDecimal(winnerQuantity));
		UUID winnerPublicId = UUID.randomUUID();
		CountDownLatch inventoryLocked = new CountDownLatch(1);
		CountDownLatch allowWinnerCommit = new CountDownLatch(1);
		AtomicLong winnerConnection = new AtomicLong();
		TransactionTemplate transactionTemplate =
				new TransactionTemplate(transactionManager);
		transactionTemplate.setIsolationLevel(
				TransactionDefinition.ISOLATION_READ_COMMITTED);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<?> winner = executor.submit(() ->
					transactionTemplate.executeWithoutResult(status -> {
						Inventory lockedInventory = inventoryRepository
								.findByIdAndRestaurantId(
										fixture.inventory().getId(),
										fixture.restaurant().getId())
								.orElseThrow();
						winnerConnection.set(currentMysqlConnectionId());
						inventoryLocked.countDown();
						await(allowWinnerCommit);
						BigDecimal quantity = new BigDecimal(winnerQuantity);
						lockedInventory.setAvailableQuantity(
								lockedInventory.getAvailableQuantity().subtract(quantity));
						lockedInventory.setReservedQuantity(
								lockedInventory.getReservedQuantity().add(quantity));
						Instant creationTime = Instant.now();
						jdbcTemplate.execute("set foreign_key_checks = 0");
						try {
							jdbcTemplate.update(
									"""
									insert into reservations (
										public_id, customer_id, restaurant_id,
										offer_id, inventory_id, quantity,
										unit_price, total_amount, currency_code,
										status, expires_at, idempotency_key,
										request_hash, version, created_at, updated_at)
									values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',
										?, ?, ?, 0, ?, ?)
									""",
									winnerPublicId.toString(),
									customer.getId(),
									fixture.restaurant().getId(),
									fixture.offer().getId(),
									fixture.inventory().getId(),
									quantity,
									new BigDecimal("80.00"),
									quantity.multiply(new BigDecimal("80.00")),
									"INR",
									creationTime.plusSeconds(600),
									key,
									winnerHash,
									creationTime,
									creationTime);
						} finally {
							jdbcTemplate.execute("set foreign_key_checks = 1");
						}
					}));

			await(inventoryLocked);
			Future<Object> allocation = executor.submit(() -> {
				try {
					return reservationService.createReservation(
							customer.getPublicId(),
							request(fixture.offer().getPublicId(), requestedQuantity),
							key);
				} catch (RuntimeException exception) {
					return exception;
				}
			});

			long waitingConnection = transactionObserver.awaitConnection(key);
			awaitInventoryLockWait(waitingConnection);
			allowWinnerCommit.countDown();
			winner.get(10, TimeUnit.SECONDS);
			Object result = allocation.get(10, TimeUnit.SECONDS);

			if (expectConflict) {
				assertTrue(result instanceof ReservationIdempotencyConflictException);
			} else {
				ReservationResponse response = (ReservationResponse) result;
				assertEquals(winnerPublicId, response.getPublicId());
			}
		}

		assertNotEquals(
				winnerConnection.get(),
				transactionObserver.connectionId(key));
		assertTrue(transactionObserver.replayObserved(key));
		assertTrue(transactionObserver.replayReadOnly(key));
		Inventory persisted = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		BigDecimal winnerAmount = new BigDecimal(winnerQuantity);
		assertEquals(
				new BigDecimal("4.000").subtract(winnerAmount),
				persisted.getAvailableQuantity());
		assertEquals(winnerAmount, persisted.getReservedQuantity());
		assertEquals(
				1,
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						fixture.inventory().getId(),
						ReservationStatus.ACTIVE).size());
	}

	private Reservation createBackedReservation(
			Fixture fixture,
			Customer customer,
			String idempotencyKey,
			String quantityValue,
			Instant expiresAt) {
		return createBackedReservation(
				fixture,
				customer,
				fixture.offer(),
				idempotencyKey,
				quantityValue,
				expiresAt);
	}

	private Reservation createBackedReservation(
			Fixture fixture,
			Customer customer,
			Offer reservationOffer,
			String idempotencyKey,
			String quantityValue,
			Instant expiresAt) {
		BigDecimal quantity = new BigDecimal(quantityValue);
		Instant createdAt = expiresAt.minusSeconds(60);
		Reservation reservation = new Reservation(
				customer,
				fixture.restaurant(),
				reservationOffer,
				fixture.inventory(),
				quantity,
				new BigDecimal("80.00"),
				quantity.multiply(new BigDecimal("80.00")),
				"INR",
				expiresAt,
				idempotencyKey,
				requestHash(
						customer.getPublicId(),
						reservationOffer.getPublicId(),
						quantity));
		reservation.initializeCreationTimestamp(createdAt);
		return reservationRepository.saveAndFlush(reservation);
	}

	private Offer createAdditionalOffer(
			Fixture fixture,
			BigDecimal offeredQuantity) {
		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(fixture.inventory());
		detection.setDetectedQuantity(fixture.inventory().getAvailableQuantity());
		detection.setThresholdQuantity(BigDecimal.ZERO);
		detection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		detection = detectionRepository.saveAndFlush(detection);

		FoodEligibilityEvaluation evaluation =
				evaluationRepository.saveAndFlush(new FoodEligibilityEvaluation(
						detection,
						"FOOD_ELIGIBILITY_V1",
						"1.0",
						"FOODSAVER_INTERNAL_V1_POLICY",
						FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
						fixture.inventory().getVersion(),
						fixture.inventory().getAvailableQuantity(),
						Instant.now()));
		Instant now = Instant.now();
		Offer offer = offerRepository.saveAndFlush(new Offer(
				fixture.restaurant(),
				fixture.product(),
				fixture.inventory(),
				evaluation,
				new BigDecimal("100.00"),
				new BigDecimal("20.00"),
				new BigDecimal("80.00"),
				"INR",
				offeredQuantity,
				now.minusSeconds(60),
				now.plusSeconds(3600)));
		createdAdditionalOffers.add(offer);
		return offer;
	}

	private String requestHash(
			UUID customerPublicId,
			UUID offerPublicId,
			BigDecimal quantity) {
		String canonical = customerPublicId
				+ "\n"
				+ offerPublicId
				+ "\n"
				+ quantity.setScale(3).toPlainString();
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(
							canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
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

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException(
						"Timed out coordinating Reservation allocation test");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					"Interrupted coordinating Reservation allocation test",
					exception);
		}
	}

	private ReservationCreateRequest request(UUID offerPublicId, String quantity) {
		ReservationCreateRequest request = new ReservationCreateRequest();
		request.setOfferPublicId(offerPublicId);
		request.setQuantity(new BigDecimal(quantity));
		return request;
	}

	private Fixture createFixture(
			BigDecimal prepared,
			BigDecimal available,
			BigDecimal reserved,
			BigDecimal sold,
			BigDecimal offered,
			int customerCount) {
		Restaurant restaurant = new Restaurant();
		restaurant.setName("Allocation Integration " + UUID.randomUUID());
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setContactEmail(UUID.randomUUID() + "@example.com");
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
		product.setName("Allocation Product");
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setBasePrice(new BigDecimal("100.00"));
		product.setCurrencyCode("INR");
		product.setStatus(ProductStatus.ACTIVE);
		product = productRepository.saveAndFlush(product);

		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setPreparedQuantity(prepared);
		inventory.setAvailableQuantity(available);
		inventory.setReservedQuantity(reserved);
		inventory.setSoldQuantity(sold);
		inventory.setInventoryDate(LocalDate.now());
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory = inventoryRepository.saveAndFlush(inventory);

		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setDetectedQuantity(available);
		detection.setThresholdQuantity(BigDecimal.ZERO);
		detection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		detection = detectionRepository.saveAndFlush(detection);

		FoodEligibilityEvaluation evaluation =
				evaluationRepository.saveAndFlush(new FoodEligibilityEvaluation(
						detection,
						"FOOD_ELIGIBILITY_V1",
						"1.0",
						"FOODSAVER_INTERNAL_V1_POLICY",
						FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
						inventory.getVersion(),
						inventory.getAvailableQuantity(),
						Instant.now()));

		Instant now = Instant.now();
		Offer offer = offerRepository.saveAndFlush(new Offer(
				restaurant,
				product,
				inventory,
				evaluation,
				new BigDecimal("100.00"),
				new BigDecimal("20.00"),
				new BigDecimal("80.00"),
				"INR",
				offered,
				now.minusSeconds(60),
				now.plusSeconds(3600)));

		ArrayList<Customer> customers = new ArrayList<>();
		for (int index = 0; index < customerCount; index++) {
			Customer customer = new Customer();
			customer.setEmail(UUID.randomUUID() + "@example.com");
			customer.setDisplayName("Allocation Customer " + index);
			customers.add(customerRepository.saveAndFlush(customer));
		}
		Fixture fixture = new Fixture(
				restaurant,
				product,
				inventory,
				offer,
				customers);
		createdFixtures.add(fixture);
		return fixture;
	}

	private record Fixture(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer,
			List<Customer> customers) {
	}

	private record Result(boolean created, boolean conflict) {
	}

	static class TestTransactionFailure extends RuntimeException {
	}

	static class TestTransactionObserver
			extends ReservationAllocationTransactionObserver {

		private final JdbcTemplate jdbcTemplate;
		private final Map<String, Long> connectionIds = new ConcurrentHashMap<>();
		private final Map<String, String> isolationLevels = new ConcurrentHashMap<>();
		private final Map<String, List<Long>> offerLockIds =
				new ConcurrentHashMap<>();
		private final Map<String, List<Long>> reservationLockIds =
				new ConcurrentHashMap<>();
		private final Map<String, TransactionBlock> persistenceBlocks =
				new ConcurrentHashMap<>();
		private final Set<String> failAfterPersistence = ConcurrentHashMap.newKeySet();
		private final Set<String> failAfterExpiry = ConcurrentHashMap.newKeySet();
		private final Set<String> replayObserved = ConcurrentHashMap.newKeySet();
		private final Set<String> replayReadOnly = ConcurrentHashMap.newKeySet();

		TestTransactionObserver(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Override
		void beforeInventoryLock(String idempotencyKey) {
			connectionIds.put(idempotencyKey, queryConnectionId());
			String isolation = jdbcTemplate.queryForObject(
					"select @@transaction_isolation",
					String.class);
			if (isolation != null) {
				isolationLevels.put(idempotencyKey, isolation);
			}
		}

		@Override
		void afterExpiryFlush(String idempotencyKey) {
			if (failAfterExpiry.contains(idempotencyKey)) {
				throw new TestTransactionFailure();
			}
		}

		@Override
		void afterOfferLocks(String idempotencyKey, List<Long> offerIds) {
			offerLockIds.put(idempotencyKey, List.copyOf(offerIds));
		}

		@Override
		void afterReservationLocks(
				String idempotencyKey,
				List<Long> reservationIds) {
			reservationLockIds.put(idempotencyKey, List.copyOf(reservationIds));
		}

		@Override
		void afterReservationPersistence(String idempotencyKey) {
			if (failAfterPersistence.contains(idempotencyKey)) {
				throw new TestTransactionFailure();
			}
			TransactionBlock block = persistenceBlocks.get(idempotencyKey);
			if (block != null) {
				block.entered().countDown();
				awaitLatch(block.release());
			}
		}

		@Override
		void insideReplayTransaction(String idempotencyKey) {
			replayObserved.add(idempotencyKey);
			if (org.springframework.transaction.support.TransactionSynchronizationManager
					.isCurrentTransactionReadOnly()) {
				replayReadOnly.add(idempotencyKey);
			}
		}

		TransactionBlock blockAfterPersistence(String idempotencyKey) {
			TransactionBlock block = new TransactionBlock(
					new CountDownLatch(1),
					new CountDownLatch(1));
			persistenceBlocks.put(idempotencyKey, block);
			return block;
		}

		void failAfterPersistence(String idempotencyKey) {
			failAfterPersistence.add(idempotencyKey);
		}

		void failAfterExpiry(String idempotencyKey) {
			failAfterExpiry.add(idempotencyKey);
		}

		long awaitConnection(String idempotencyKey) {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (System.nanoTime() < deadline) {
				Long connectionId = connectionIds.get(idempotencyKey);
				if (connectionId != null) {
					return connectionId;
				}
				Thread.yield();
			}
			throw new AssertionError("Allocation connection was not observed");
		}

		long connectionId(String idempotencyKey) {
			return connectionIds.get(idempotencyKey);
		}

		String isolation(String idempotencyKey) {
			return isolationLevels.get(idempotencyKey);
		}

		boolean replayObserved(String idempotencyKey) {
			return replayObserved.contains(idempotencyKey);
		}

		boolean replayReadOnly(String idempotencyKey) {
			return replayReadOnly.contains(idempotencyKey);
		}

		List<Long> offerLocks(String idempotencyKey) {
			return offerLockIds.get(idempotencyKey);
		}

		List<Long> reservationLocks(String idempotencyKey) {
			return reservationLockIds.get(idempotencyKey);
		}

		void reset() {
			persistenceBlocks.values().forEach(block -> block.release().countDown());
			connectionIds.clear();
			isolationLevels.clear();
			offerLockIds.clear();
			reservationLockIds.clear();
			persistenceBlocks.clear();
			failAfterPersistence.clear();
			failAfterExpiry.clear();
			replayObserved.clear();
			replayReadOnly.clear();
		}

		private long queryConnectionId() {
			Long connectionId = jdbcTemplate.queryForObject(
					"select connection_id()",
					Long.class);
			if (connectionId == null) {
				throw new IllegalStateException(
						"MySQL connection ID was not available");
			}
			return connectionId;
		}

		private void awaitLatch(CountDownLatch latch) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException(
							"Timed out waiting in Reservation transaction observer");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted in Reservation transaction observer",
						exception);
			}
		}

		record TransactionBlock(
				CountDownLatch entered,
				CountDownLatch release) {
		}
	}

	static class TestPreflightObserver
			extends ReservationAllocationPreflightObserver {

		private final JdbcTemplate jdbcTemplate;
		private volatile PreflightBlock block;
		private volatile String isolation;

		TestPreflightObserver(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Override
		public void afterInventorySnapshotRead() {
			isolation = jdbcTemplate.queryForObject(
					"select @@transaction_isolation",
					String.class);
			PreflightBlock current = block;
			if (current != null) {
				current.entered().countDown();
				awaitLatch(current.release());
			}
		}

		PreflightBlock blockAfterInventorySnapshotRead() {
			PreflightBlock configured = new PreflightBlock(
					new CountDownLatch(1),
					new CountDownLatch(1));
			block = configured;
			return configured;
		}

		void reset() {
			PreflightBlock current = block;
			if (current != null) {
				current.release().countDown();
			}
			block = null;
			isolation = null;
		}

		String isolation() {
			return isolation;
		}

		private void awaitLatch(CountDownLatch latch) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException(
							"Timed out waiting in preflight observer");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted in preflight observer",
						exception);
			}
		}

		record PreflightBlock(
				CountDownLatch entered,
				CountDownLatch release) {
		}
	}

	static class TestSqlStatementInspector implements StatementInspector {

		private final List<String> statements = new CopyOnWriteArrayList<>();

		@Override
		public String inspect(String sql) {
			if (sql != null) {
				statements.add(sql);
			}
			return sql;
		}

		String lockingSelectFor(String tableName) {
			return statements.stream()
					.filter(sql -> {
						String normalized = sql
								.replace("`", "")
								.replace("\"", "")
								.replaceAll("\\s+", " ")
								.toLowerCase();
						return normalized.contains(" from " + tableName + " ")
								&& normalized.contains(" for update");
					})
					.findFirst()
					.orElse(null);
		}

		void clear() {
			statements.clear();
		}
	}

	@TestConfiguration
	static class MarkerConfiguration {

		@Bean
		@Primary
		TestTransactionObserver testReservationAllocationTransactionObserver(
				JdbcTemplate jdbcTemplate) {
			return new TestTransactionObserver(jdbcTemplate);
		}

		@Bean
		@Primary
		TestPreflightObserver testReservationAllocationPreflightObserver(
				JdbcTemplate jdbcTemplate) {
			return new TestPreflightObserver(jdbcTemplate);
		}

		@Bean
		TestSqlStatementInspector testSqlStatementInspector() {
			return new TestSqlStatementInspector();
		}

		@Bean
		HibernatePropertiesCustomizer testStatementInspectorCustomizer(
				TestSqlStatementInspector inspector) {
			return properties -> properties.put(
					AvailableSettings.STATEMENT_INSPECTOR,
					inspector);
		}

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		ApplicationRunner reservationAllocationMarkerInitializer(
				OrderingReconciliationMarkerRepository markerRepository) {
			return arguments -> {
				markerRepository.deleteById(
						ReservationAllocationRelease.IDENTIFIER);
				markerRepository.saveAndFlush(new OrderingReconciliationMarker(
						ReservationAllocationRelease.IDENTIFIER,
						Instant.now()));
			};
		}
	}
}
