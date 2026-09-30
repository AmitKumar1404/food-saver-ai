package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.foodsaver.config.ReservationAllocationRelease;
import com.foodsaver.dto.request.OrderCreateRequest;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.OrderItem;
import com.foodsaver.entity.OrderingReconciliationMarker;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OrderStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderIdempotencyConflictException;
import com.foodsaver.exception.ReservationNotFoundException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.FoodEligibilityEvaluationRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.OrderItemRepository;
import com.foodsaver.repository.OrderRepository;
import com.foodsaver.repository.OrderingReconciliationMarkerRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;
import com.foodsaver.service.OrderService;
import com.foodsaver.service.ReservationService;

@SpringBootTest(properties = "foodsaver.ordering.allocation-enabled=true")
@ActiveProfiles("test")
@Import(OrderManagementIntegrationTests.MarkerConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderManagementIntegrationTests {

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

	private final List<Fixture> fixtures = new ArrayList<>();

	@Autowired
	private OrderService orderService;
	@Autowired
	private ReservationService reservationService;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private OrderItemRepository orderItemRepository;
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
	private JdbcTemplate jdbcTemplate;
	@PersistenceContext
	private EntityManager entityManager;
	@Autowired
	private TestOrderObserver observer;
	@Autowired
	private TestOrderSqlStatementInspector sqlStatementInspector;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterAll
	void cleanMarker() {
		markerRepository.deleteById(ReservationAllocationRelease.IDENTIFIER);
	}

	@AfterEach
	void cleanData() {
		observer.reset();
		sqlStatementInspector.clear();
		for (Fixture fixture : fixtures.reversed()) {
			List<com.foodsaver.entity.Order> orders = fixture.customers().stream()
					.flatMap(customer -> orderRepository
							.findAllByCustomerId(customer.getId())
							.stream())
					.toList();
			List<Long> orderIds = orders.stream()
					.map(com.foodsaver.entity.Order::getId)
					.toList();
			if (!orderIds.isEmpty()) {
				orderItemRepository.deleteAll(
						orderItemRepository.findAllByOrderIdIn(orderIds));
				orderRepository.deleteAll(orders);
			}
			List<Long> reservationIds = Arrays.stream(ReservationStatus.values())
					.flatMap(status -> reservationRepository
							.findAllocationTargetsByInventoryIdAndStatus(
									fixture.inventory().getId(),
									status)
							.stream())
					.map(target -> target.reservationId())
					.toList();
			reservationRepository.deleteAllById(reservationIds);
			offerRepository.deleteById(fixture.offer().getId());
			evaluationRepository.deleteById(
					fixture.offer().getEligibilityEvaluation().getId());
			detectionRepository.deleteById(
					fixture.offer().getEligibilityEvaluation()
							.getSurplusDetection().getId());
			inventoryRepository.deleteById(fixture.inventory().getId());
			productRepository.deleteById(fixture.product().getId());
			customerRepository.deleteAll(fixture.customers());
			restaurantRepository.deleteById(fixture.restaurant().getId());
		}
		fixtures.clear();
	}

	@Test
	void convertsReservationUsingSnapshotsWithoutChangingInventory() {
		Fixture fixture = createFixture(2);
		Reservation reservation = reserve(
				fixture,
				fixture.customers().getFirst(),
				"reserve-success-" + UUID.randomUUID());
		Inventory before = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		jdbcTemplate.update(
				"update offers set offer_price = ? where id = ?",
				new BigDecimal("45.00"),
				fixture.offer().getId());
		jdbcTemplate.update(
				"""
				update products
				set base_price = ?, name = ?
				where id = ?
				""",
				new BigDecimal("999.00"),
				"Name At Conversion",
				fixture.product().getId());
		entityManager.clear();

		OrderResponse response = orderService.createOrder(
				fixture.customers().getFirst().getPublicId(),
				orderRequest(reservation.getPublicId()),
				"order-success-" + UUID.randomUUID());

		Reservation converted = reservationRepository
				.findById(reservation.getId())
				.orElseThrow();
		Inventory after = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(OrderStatus.CONFIRMED, response.getStatus());
		assertEquals(ReservationStatus.CONVERTED, converted.getStatus());
		assertEquals(new BigDecimal("80.00"), response.getItem().getUnitPrice());
		assertEquals(new BigDecimal("80.00"), response.getItem().getTotalAmount());
		assertEquals("Name At Conversion", response.getItem().getProductNameSnapshot());
		assertEquals(before.getPreparedQuantity(), after.getPreparedQuantity());
		assertEquals(before.getAvailableQuantity(), after.getAvailableQuantity());
		assertEquals(before.getReservedQuantity(), after.getReservedQuantity());
		assertEquals(before.getSoldQuantity(), after.getSoldQuantity());
		assertEquals(response.getConfirmedAt(), response.getCreatedAt());
		assertEquals(0, response.getConfirmedAt().getNano() % 1_000);

		jdbcTemplate.update(
				"update products set name = ? where id = ?",
				"Name After Conversion",
				fixture.product().getId());
		entityManager.clear();
		OrderResponse reloaded = orderService.getOrder(
				fixture.customers().getFirst().getPublicId(),
				response.getPublicId());
		assertEquals("Name At Conversion", reloaded.getItem().getProductNameSnapshot());
		assertEquals(response.getItem().getPublicId(), reloaded.getItem().getPublicId());
		assertEquals(response.getConfirmedAt(), reloaded.getConfirmedAt());
		assertEquals(response.getCreatedAt(), reloaded.getCreatedAt());
		assertEquals(response.getUpdatedAt(), reloaded.getUpdatedAt());
		assertEquals(response.getItem().getCreatedAt(), reloaded.getItem().getCreatedAt());
		assertEquals(0, converted.getConvertedAt().getNano() % 1_000);
	}

	@Test
	void replaysSameKeyAndRejectsDifferentRequestHash() {
		Fixture fixture = createFixture(1);
		Reservation first = reserve(
				fixture,
				fixture.customers().getFirst(),
				"reserve-idempotent-" + UUID.randomUUID());
		String key = "order-idempotent-" + UUID.randomUUID();

		OrderResponse created = orderService.createOrder(
				fixture.customers().getFirst().getPublicId(),
				orderRequest(first.getPublicId()),
				key);
		OrderResponse replay = orderService.createOrder(
				fixture.customers().getFirst().getPublicId(),
				orderRequest(first.getPublicId()),
				key);

		assertEquals(created.getPublicId(), replay.getPublicId());
		assertThrows(
				OrderIdempotencyConflictException.class,
				() -> orderService.createOrder(
						fixture.customers().getFirst().getPublicId(),
						orderRequest(UUID.randomUUID()),
						key));
	}

	@Test
	void databaseConstraintsEnforceOrderIdempotencyAndSingleItemShape() {
		Fixture fixture = createFixture(1);
		Customer customer = fixture.customers().getFirst();
		Reservation firstReservation = reserve(
				fixture,
				customer,
				"reserve-constraints-a-" + UUID.randomUUID());
		String firstKey = "order-constraints-a-" + UUID.randomUUID();
		OrderResponse response = orderService.createOrder(
				customer.getPublicId(),
				orderRequest(firstReservation.getPublicId()),
				firstKey);
		com.foodsaver.entity.Order firstOrder = orderRepository
				.findByPublicIdAndCustomerPublicId(
						response.getPublicId(),
						customer.getPublicId())
				.orElseThrow();

		com.foodsaver.entity.Order duplicateKey = new com.foodsaver.entity.Order(
				customer,
				fixture.restaurant(),
				firstReservation.getTotalAmount(),
				firstReservation.getCurrencyCode(),
				firstKey,
				"b".repeat(64),
				Instant.now());
		assertThrows(
				DataIntegrityViolationException.class,
				() -> orderRepository.saveAndFlush(duplicateKey));

		com.foodsaver.entity.Order secondOrder = orderRepository.saveAndFlush(
				new com.foodsaver.entity.Order(
						customer,
						fixture.restaurant(),
						firstReservation.getTotalAmount(),
						firstReservation.getCurrencyCode(),
						"order-constraints-b-" + UUID.randomUUID(),
						"c".repeat(64),
						Instant.now()));
		assertNotEquals(firstOrder.getPublicId(), secondOrder.getPublicId());

		OrderItem duplicateReservation = new OrderItem(
				secondOrder,
				firstReservation,
				fixture.offer(),
				fixture.product(),
				fixture.inventory(),
				fixture.product().getName(),
				firstReservation.getQuantity(),
				firstReservation.getUnitPrice(),
				firstReservation.getTotalAmount(),
				firstReservation.getCurrencyCode(),
				Instant.now());
		assertThrows(
				DataIntegrityViolationException.class,
				() -> orderItemRepository.saveAndFlush(duplicateReservation));

		Reservation secondReservation = reserve(
				fixture,
				customer,
				"reserve-constraints-b-" + UUID.randomUUID());
		OrderItem duplicateOrder = new OrderItem(
				firstOrder,
				secondReservation,
				fixture.offer(),
				fixture.product(),
				fixture.inventory(),
				fixture.product().getName(),
				secondReservation.getQuantity(),
				secondReservation.getUnitPrice(),
				secondReservation.getTotalAmount(),
				secondReservation.getCurrencyCode(),
				Instant.now());
		assertThrows(
				DataIntegrityViolationException.class,
				() -> orderItemRepository.saveAndFlush(duplicateOrder));

		assertEquals(
				6,
				jdbcTemplate.queryForObject(
						"""
						select min(datetime_precision)
						from information_schema.columns
						where table_schema = database()
						  and table_name in ('customer_orders', 'order_items')
						  and column_name in (
							'confirmed_at', 'created_at', 'updated_at')
						""",
						Integer.class));
	}

	@Test
	void rejectsExpiredCancelledForeignAndMismatchedLedgerState() {
		Fixture expiredFixture = createFixture(1);
		Reservation expired = reserve(
				expiredFixture,
				expiredFixture.customers().getFirst(),
				"reserve-expired-" + UUID.randomUUID());
		reservationRepository.delete(expired);
		reservationRepository.flush();
		Reservation expiredReplacement = new Reservation(
				expired.getCustomer(),
				expired.getRestaurant(),
				expired.getOffer(),
				expired.getInventory(),
				expired.getQuantity(),
				expired.getUnitPrice(),
				expired.getTotalAmount(),
				expired.getCurrencyCode(),
				Instant.now().minusSeconds(60),
				"expired-direct-" + UUID.randomUUID(),
				"expired-direct-hash-" + UUID.randomUUID());
		expiredReplacement.initializeCreationTimestamp(
				Instant.now().minusSeconds(120));
		expired = reservationRepository.saveAndFlush(expiredReplacement);
		Reservation expiredReservation = expired;
		assertThrows(
				OrderConversionConflictException.class,
				() -> convert(
						expiredFixture,
						expiredFixture.customers().getFirst(),
						expiredReservation,
						"expired"));

		Fixture cancelledFixture = createFixture(1);
		Reservation cancelled = reserve(
				cancelledFixture,
				cancelledFixture.customers().getFirst(),
				"reserve-cancelled-" + UUID.randomUUID());
		cancelled.setStatus(ReservationStatus.CANCELLED);
		cancelled.setCancelledAt(Instant.now());
		reservationRepository.saveAndFlush(cancelled);
		assertThrows(
				OrderConversionConflictException.class,
				() -> convert(
						cancelledFixture,
						cancelledFixture.customers().getFirst(),
						cancelled,
						"cancelled"));

		Fixture foreignFixture = createFixture(2);
		Reservation foreign = reserve(
				foreignFixture,
				foreignFixture.customers().getFirst(),
				"reserve-foreign-" + UUID.randomUUID());
		assertThrows(
				ReservationNotFoundException.class,
				() -> convert(
						foreignFixture,
						foreignFixture.customers().get(1),
						foreign,
						"foreign"));

		Fixture ledgerFixture = createFixture(1);
		Reservation ledger = reserve(
				ledgerFixture,
				ledgerFixture.customers().getFirst(),
				"reserve-ledger-" + UUID.randomUUID());
		Inventory inventory = inventoryRepository
				.findById(ledgerFixture.inventory().getId())
				.orElseThrow();
		inventory.setAvailableQuantity(
				inventory.getAvailableQuantity().subtract(BigDecimal.ONE));
		inventory.setReservedQuantity(
				inventory.getReservedQuantity().add(BigDecimal.ONE));
		inventoryRepository.saveAndFlush(inventory);
		assertThrows(
				OrderConversionConflictException.class,
				() -> convert(
						ledgerFixture,
						ledgerFixture.customers().getFirst(),
						ledger,
						"ledger"));
	}

	@Test
	void rejectsCurrencyAndRelationshipCorruption() {
		Fixture currencyFixture = createFixture(1);
		Reservation currencyReservation = reserve(
				currencyFixture,
				currencyFixture.customers().getFirst(),
				"reserve-currency-" + UUID.randomUUID());
		jdbcTemplate.update(
				"update reservations set currency_code = ? where id = ?",
				"USD",
				currencyReservation.getId());
		entityManager.clear();
		assertThrows(
				OrderConversionConflictException.class,
				() -> convert(
						currencyFixture,
						currencyFixture.customers().getFirst(),
						currencyReservation,
						"currency"));

		Fixture relationshipFixture = createFixture(1);
		Fixture unrelatedFixture = createFixture(1);
		Reservation relationshipReservation = reserve(
				relationshipFixture,
				relationshipFixture.customers().getFirst(),
				"reserve-relationship-" + UUID.randomUUID());
		jdbcTemplate.update(
				"update reservations set inventory_id = ? where id = ?",
				unrelatedFixture.inventory().getId(),
				relationshipReservation.getId());
		entityManager.clear();
		assertThrows(
				OrderConversionConflictException.class,
				() -> convert(
						relationshipFixture,
						relationshipFixture.customers().getFirst(),
						relationshipReservation,
						"relationship"));
	}

	@Test
	void concurrentDifferentKeysConvertReservationExactlyOnce() throws Exception {
		Fixture fixture = createFixture(1);
		Customer customer = fixture.customers().getFirst();
		Reservation reservation = reserve(
				fixture,
				customer,
				"reserve-race-" + UUID.randomUUID());
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Object> first = executor.submit(() -> convertAfterBarrier(
					start,
					customer,
					reservation,
					"order-race-a-" + UUID.randomUUID()));
			Future<Object> second = executor.submit(() -> convertAfterBarrier(
					start,
					customer,
					reservation,
					"order-race-b-" + UUID.randomUUID()));
			List<Object> results = List.of(first.get(), second.get());

			assertEquals(
					1,
					results.stream().filter(OrderResponse.class::isInstance).count());
			assertEquals(
					1,
					results.stream()
							.filter(OrderConversionConflictException.class::isInstance)
							.count());
		}

		assertEquals(
				1,
				orderItemRepository.existsByReservationId(reservation.getId()) ? 1 : 0);
		assertEquals(
				ReservationStatus.CONVERTED,
				reservationRepository.findById(reservation.getId())
						.orElseThrow()
						.getStatus());
	}

	@Test
	void concurrentSameKeyReplaysOneOrder() throws Exception {
		Fixture fixture = createFixture(1);
		Customer customer = fixture.customers().getFirst();
		Reservation reservation = reserve(
				fixture,
				customer,
				"reserve-same-key-" + UUID.randomUUID());
		String key = "order-same-key-" + UUID.randomUUID();
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<OrderResponse> first = executor.submit(() -> {
				start.await();
				return orderService.createOrder(
						customer.getPublicId(),
						orderRequest(reservation.getPublicId()),
						key);
			});
			Future<OrderResponse> second = executor.submit(() -> {
				start.await();
				return orderService.createOrder(
						customer.getPublicId(),
						orderRequest(reservation.getPublicId()),
						key);
			});
			assertEquals(first.get().getPublicId(), second.get().getPublicId());
		}
	}

	@Test
	void namedIdempotencyConstraintRaceRollsBackBeforeRequiresNewReplay()
			throws Exception {
		Fixture fixture = createFixture(1);
		Customer customer = fixture.customers().getFirst();
		Reservation losingReservation = reserve(
				fixture,
				customer,
				"reserve-constraint-loser-" + UUID.randomUUID());
		Reservation winningReservation = reserve(
				fixture,
				customer,
				"reserve-constraint-winner-" + UUID.randomUUID());
		String key = "order-constraint-race-" + UUID.randomUUID();
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
						inventoryRepository.findByIdAndRestaurantId(
								fixture.inventory().getId(),
								fixture.restaurant().getId())
								.orElseThrow();
						winnerConnection.set(currentMysqlConnectionId());
						inventoryLocked.countDown();
						await(allowWinnerCommit);
						insertWinningOrder(
								fixture,
								customer,
								winningReservation,
								key,
								winnerPublicId);
					}));

			await(inventoryLocked);
			Future<Object> loser = executor.submit(() -> {
				try {
					return convert(
							fixture,
							customer,
							losingReservation,
							key);
				} catch (RuntimeException exception) {
					return exception;
				}
			});

			long waitingConnection = observer.awaitConnection(key);
			awaitInventoryLockWait(waitingConnection);
			allowWinnerCommit.countDown();
			winner.get(10, TimeUnit.SECONDS);

			assertTrue(
					loser.get(10, TimeUnit.SECONDS)
							instanceof OrderIdempotencyConflictException);
		}

		assertNotEquals(winnerConnection.get(), observer.connectionId(key));
		assertTrue(observer.replayObserved(key));
		assertTrue(observer.replayReadOnly(key));
		assertEquals(
				ReservationStatus.ACTIVE,
				reservationRepository.findById(losingReservation.getId())
						.orElseThrow()
						.getStatus());
		assertTrue(!orderItemRepository.existsByReservationId(
				losingReservation.getId()));
		assertEquals(1, orderRepository.findAllByCustomerId(customer.getId()).size());
		assertEquals(
				ReservationStatus.CONVERTED,
				reservationRepository.findById(winningReservation.getId())
						.orElseThrow()
						.getStatus());
		assertTrue(orderRepository
				.findByPublicIdAndCustomerPublicId(
						winnerPublicId,
						customer.getPublicId())
				.isPresent());
	}

	@Test
	void concurrentConversionsWaitForTheSameInventoryLock() throws Exception {
		Fixture fixture = createFixture(2);
		Reservation firstReservation = reserve(
				fixture,
				fixture.customers().getFirst(),
				"reserve-lock-wait-a-" + UUID.randomUUID());
		Reservation secondReservation = reserve(
				fixture,
				fixture.customers().get(1),
				"reserve-lock-wait-b-" + UUID.randomUUID());
		String firstKey = "order-lock-wait-a-" + UUID.randomUUID();
		String secondKey = "order-lock-wait-b-" + UUID.randomUUID();
		TestOrderObserver.TransactionBlock block =
				observer.blockAfterInventoryLock(firstKey);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<OrderResponse> first = executor.submit(() -> convert(
					fixture,
					fixture.customers().getFirst(),
					firstReservation,
					firstKey));
			await(block.entered());

			Future<OrderResponse> second = executor.submit(() -> convert(
					fixture,
					fixture.customers().get(1),
					secondReservation,
					secondKey));
			long waitingConnection = observer.awaitConnection(secondKey);
			awaitInventoryLockWait(waitingConnection);

			block.release().countDown();
			assertTrue(first.get(10, TimeUnit.SECONDS).getPublicId() != null);
			assertTrue(second.get(10, TimeUnit.SECONDS).getPublicId() != null);
		}

		assertNotEquals(
				observer.connectionId(firstKey),
				observer.connectionId(secondKey));
		assertEquals(
				ReservationStatus.CONVERTED,
				reservationRepository.findById(firstReservation.getId())
						.orElseThrow()
						.getStatus());
		assertEquals(
				ReservationStatus.CONVERTED,
				reservationRepository.findById(secondReservation.getId())
						.orElseThrow()
						.getStatus());
	}

	@Test
	void failuresAtEveryMutationBoundaryRollbackEverything() {
		assertRollback("after-order", RollbackPoint.AFTER_ORDER);
		assertRollback("after-item", RollbackPoint.AFTER_ITEM);
		assertRollback("after-conversion", RollbackPoint.AFTER_CONVERSION);
		assertRollback("final-invariant", RollbackPoint.FINAL_INVARIANT);
	}

	@Test
	void conversionRunsReadCommittedOnIndependentConnection() {
		Fixture fixture = createFixture(1);
		Reservation reservation = reserve(
				fixture,
				fixture.customers().getFirst(),
				"reserve-isolation-" + UUID.randomUUID());
		String key = "order-isolation-" + UUID.randomUUID();

		convert(fixture, fixture.customers().getFirst(), reservation, key);

		assertEquals("READ-COMMITTED", observer.isolation(key));
		assertTrue(observer.connectionId(key) > 0);
		assertTrue(observer.transactionActive(key));
	}

	@Test
	void conversionIssuesOrderedOfferAndReservationLockingSql() {
		Fixture fixture = createFixture(1);
		Reservation reservation = reserve(
				fixture,
				fixture.customers().getFirst(),
				"reserve-lock-sql-" + UUID.randomUUID());
		sqlStatementInspector.clear();

		convert(
				fixture,
				fixture.customers().getFirst(),
				reservation,
				"order-lock-sql-" + UUID.randomUUID());

		assertOrderedLockingSql(
				sqlStatementInspector.lockingSelectFor("offers"),
				"offers");
		assertOrderedLockingSql(
				sqlStatementInspector.lockingSelectFor("reservations"),
				"reservations");
	}

	private void assertOrderedLockingSql(String sql, String tableName) {
		assertTrue(sql != null, "Missing locking SQL for " + tableName);
		String normalized = sql
				.replace("`", "")
				.replace("\"", "")
				.replaceAll("\\s+", " ")
				.toLowerCase();
		String alias = Pattern.compile("\\bfrom " + tableName + " ([a-z0-9_]+)")
				.matcher(normalized)
				.results()
				.findFirst()
				.orElseThrow()
				.group(1);
		assertTrue(
				normalized.matches(
						".*\\border by " + Pattern.quote(alias)
								+ "\\.id(?: asc)?(?:\\s|,).*"),
				normalized);
		assertTrue(normalized.contains(" for update"), normalized);
	}

	private void insertWinningOrder(
			Fixture fixture,
			Customer customer,
			Reservation reservation,
			String key,
			UUID orderPublicId) {
		Instant transactionTime = Reservation.normalizeTimestamp(Instant.now());
		Timestamp timestamp = Timestamp.from(transactionTime);
		jdbcTemplate.execute("set foreign_key_checks = 0");
		try {
			jdbcTemplate.update(
					"""
					insert into customer_orders (
						public_id, customer_id, restaurant_id, status,
						total_amount, currency_code, idempotency_key,
						request_hash, confirmed_at, created_at, updated_at, version)
					values (?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, 0)
					""",
					orderPublicId.toString(),
					customer.getId(),
					fixture.restaurant().getId(),
					reservation.getTotalAmount(),
					reservation.getCurrencyCode(),
					key,
					orderRequestHash(
							customer.getPublicId(),
							reservation.getPublicId()),
					timestamp,
					timestamp,
					timestamp);
			Long orderId = jdbcTemplate.queryForObject(
					"select id from customer_orders where public_id = ?",
					Long.class,
					orderPublicId.toString());
			jdbcTemplate.update(
					"""
					insert into order_items (
						public_id, order_id, reservation_id, offer_id,
						product_id, inventory_id, product_name_snapshot,
						quantity, unit_price, total_amount, currency_code, created_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""",
					UUID.randomUUID().toString(),
					orderId,
					reservation.getId(),
					fixture.offer().getId(),
					fixture.product().getId(),
					fixture.inventory().getId(),
					fixture.product().getName(),
					reservation.getQuantity(),
					reservation.getUnitPrice(),
					reservation.getTotalAmount(),
					reservation.getCurrencyCode(),
					timestamp);
			jdbcTemplate.update(
					"""
					update reservations
					set status = 'CONVERTED', converted_at = ?,
						updated_at = ?, version = version + 1
					where id = ?
					""",
					timestamp,
					timestamp,
					reservation.getId());
		} finally {
			jdbcTemplate.execute("set foreign_key_checks = 1");
		}
	}

	private String orderRequestHash(
			UUID customerPublicId,
			UUID reservationPublicId) {
		String canonical = customerPublicId + "\n" + reservationPublicId;
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
						"Timed out coordinating Order conversion test");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					"Interrupted coordinating Order conversion test",
					exception);
		}
	}

	private void assertRollback(String suffix, RollbackPoint rollbackPoint) {
		Fixture fixture = createFixture(1);
		Customer customer = fixture.customers().getFirst();
		Reservation reservation = reserve(
				fixture,
				customer,
				"reserve-rollback-" + UUID.randomUUID());
		Inventory before = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		String key = "order-rollback-" + suffix + "-" + UUID.randomUUID();
		switch (rollbackPoint) {
			case AFTER_ORDER -> observer.failAfterOrder(key);
			case AFTER_ITEM -> observer.failAfterItem(key);
			case AFTER_CONVERSION -> observer.failAfterConversion(key);
			case FINAL_INVARIANT -> observer.corruptAfterConversion(key);
		}

		if (rollbackPoint == RollbackPoint.FINAL_INVARIANT) {
			assertThrows(
					OrderConversionConflictException.class,
					() -> convert(fixture, customer, reservation, key));
		} else {
			assertThrows(
					TestOrderFailure.class,
					() -> convert(fixture, customer, reservation, key));
		}

		assertTrue(orderRepository.findAllByCustomerId(customer.getId()).isEmpty());
		assertTrue(!orderItemRepository.existsByReservationId(reservation.getId()));
		Reservation persisted = reservationRepository
				.findById(reservation.getId())
				.orElseThrow();
		Inventory after = inventoryRepository
				.findById(fixture.inventory().getId())
				.orElseThrow();
		assertEquals(ReservationStatus.ACTIVE, persisted.getStatus());
		assertEquals(null, persisted.getConvertedAt());
		assertEquals(before.getPreparedQuantity(), after.getPreparedQuantity());
		assertEquals(before.getAvailableQuantity(), after.getAvailableQuantity());
		assertEquals(before.getReservedQuantity(), after.getReservedQuantity());
		assertEquals(before.getSoldQuantity(), after.getSoldQuantity());
	}

	private Object convertAfterBarrier(
			CyclicBarrier barrier,
			Customer customer,
			Reservation reservation,
			String key) throws Exception {
		barrier.await();
		try {
			return orderService.createOrder(
					customer.getPublicId(),
					orderRequest(reservation.getPublicId()),
					key);
		} catch (OrderConversionConflictException exception) {
			return exception;
		}
	}

	private OrderResponse convert(
			Fixture fixture,
			Customer customer,
			Reservation reservation,
			String key) {
		return orderService.createOrder(
				customer.getPublicId(),
				orderRequest(reservation.getPublicId()),
				key);
	}

	private Reservation reserve(
			Fixture fixture,
			Customer customer,
			String key) {
		ReservationCreateRequest request = new ReservationCreateRequest();
		request.setOfferPublicId(fixture.offer().getPublicId());
		request.setQuantity(new BigDecimal("1.000"));
		var response = reservationService.createReservation(
				customer.getPublicId(),
				request,
				key);
		return reservationRepository.findByPublicId(response.getPublicId())
				.orElseThrow();
	}

	private OrderCreateRequest orderRequest(UUID reservationPublicId) {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setReservationPublicId(reservationPublicId);
		return request;
	}

	private Fixture createFixture(int customerCount) {
		Restaurant restaurant = new Restaurant();
		restaurant.setName("Order Integration " + UUID.randomUUID());
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
		product.setName("Order Product");
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setBasePrice(new BigDecimal("100.00"));
		product.setCurrencyCode("INR");
		product.setStatus(ProductStatus.ACTIVE);
		product = productRepository.saveAndFlush(product);

		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setPreparedQuantity(new BigDecimal("5.000"));
		inventory.setAvailableQuantity(new BigDecimal("5.000"));
		inventory.setReservedQuantity(BigDecimal.ZERO);
		inventory.setSoldQuantity(BigDecimal.ZERO);
		inventory.setInventoryDate(LocalDate.now());
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory = inventoryRepository.saveAndFlush(inventory);

		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setDetectedQuantity(inventory.getAvailableQuantity());
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
				new BigDecimal("5.000"),
				now.minusSeconds(60),
				now.plusSeconds(3600)));

		List<Customer> customers = new ArrayList<>();
		for (int index = 0; index < customerCount; index++) {
			Customer customer = new Customer();
			customer.setEmail(UUID.randomUUID() + "@example.com");
			customer.setDisplayName("Order Customer " + index);
			customers.add(customerRepository.saveAndFlush(customer));
		}
		Fixture fixture = new Fixture(
				restaurant,
				product,
				inventory,
				offer,
				customers);
		fixtures.add(fixture);
		return fixture;
	}

	private record Fixture(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer,
			List<Customer> customers) {
	}

	private enum RollbackPoint {
		AFTER_ORDER,
		AFTER_ITEM,
		AFTER_CONVERSION,
		FINAL_INVARIANT
	}

	static class TestOrderFailure extends RuntimeException {
	}

	static class TestOrderObserver extends OrderConversionTransactionObserver {

		private final JdbcTemplate jdbcTemplate;
		private final java.util.Set<String> failAfterOrder =
				ConcurrentHashMap.newKeySet();
		private final java.util.Set<String> failAfterItem =
				ConcurrentHashMap.newKeySet();
		private final java.util.Set<String> failAfterConversion =
				ConcurrentHashMap.newKeySet();
		private final java.util.Set<String> corruptAfterConversion =
				ConcurrentHashMap.newKeySet();
		private final java.util.Map<String, String> isolation =
				new ConcurrentHashMap<>();
		private final java.util.Map<String, Long> connectionIds =
				new ConcurrentHashMap<>();
		private final java.util.Set<String> transactionActive =
				ConcurrentHashMap.newKeySet();
		private final java.util.Set<String> replayObserved =
				ConcurrentHashMap.newKeySet();
		private final java.util.Set<String> replayReadOnly =
				ConcurrentHashMap.newKeySet();
		private final java.util.Map<String, TransactionBlock> inventoryBlocks =
				new ConcurrentHashMap<>();

		TestOrderObserver(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		@Override
		void beforeInventoryLock(String idempotencyKey) {
			connectionIds.put(
					idempotencyKey,
					jdbcTemplate.queryForObject(
							"select connection_id()",
							Long.class));
			isolation.put(
					idempotencyKey,
					jdbcTemplate.queryForObject(
							"select @@transaction_isolation",
							String.class));
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				transactionActive.add(idempotencyKey);
			}
		}

		@Override
		void afterInventoryLock(String idempotencyKey) {
			TransactionBlock block = inventoryBlocks.get(idempotencyKey);
			if (block != null) {
				block.entered().countDown();
				awaitLatch(block.release());
			}
		}

		@Override
		void afterOrderPersistence(String idempotencyKey) {
			if (failAfterOrder.contains(idempotencyKey)) {
				throw new TestOrderFailure();
			}
		}

		@Override
		void afterOrderItemPersistence(String idempotencyKey) {
			if (failAfterItem.contains(idempotencyKey)) {
				throw new TestOrderFailure();
			}
		}

		@Override
		void afterReservationConversion(
				String idempotencyKey,
				Inventory inventory) {
			if (failAfterConversion.contains(idempotencyKey)) {
				throw new TestOrderFailure();
			}
			if (corruptAfterConversion.contains(idempotencyKey)) {
				inventory.setAvailableQuantity(
						inventory.getAvailableQuantity().subtract(BigDecimal.ONE));
				inventory.setReservedQuantity(
						inventory.getReservedQuantity().add(BigDecimal.ONE));
			}
		}

		@Override
		void insideReplayTransaction(String idempotencyKey) {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				replayObserved.add(idempotencyKey);
			}
			if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
				replayReadOnly.add(idempotencyKey);
			}
		}

		void failAfterOrder(String key) {
			failAfterOrder.add(key);
		}

		void failAfterItem(String key) {
			failAfterItem.add(key);
		}

		void failAfterConversion(String key) {
			failAfterConversion.add(key);
		}

		void corruptAfterConversion(String key) {
			corruptAfterConversion.add(key);
		}

		String isolation(String key) {
			return isolation.get(key);
		}

		long connectionId(String key) {
			return connectionIds.get(key);
		}

		boolean transactionActive(String key) {
			return transactionActive.contains(key);
		}

		TransactionBlock blockAfterInventoryLock(String key) {
			TransactionBlock block = new TransactionBlock(
					new CountDownLatch(1),
					new CountDownLatch(1));
			inventoryBlocks.put(key, block);
			return block;
		}

		long awaitConnection(String key) {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
			while (System.nanoTime() < deadline) {
				Long connectionId = connectionIds.get(key);
				if (connectionId != null) {
					return connectionId;
				}
				Thread.yield();
			}
			throw new AssertionError(
					"Order transaction did not expose a MySQL connection");
		}

		boolean replayObserved(String key) {
			return replayObserved.contains(key);
		}

		boolean replayReadOnly(String key) {
			return replayReadOnly.contains(key);
		}

		void reset() {
			failAfterOrder.clear();
			failAfterItem.clear();
			failAfterConversion.clear();
			corruptAfterConversion.clear();
			isolation.clear();
			connectionIds.clear();
			transactionActive.clear();
			replayObserved.clear();
			replayReadOnly.clear();
			inventoryBlocks.clear();
		}

		private void awaitLatch(CountDownLatch latch) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException(
							"Timed out waiting in Order transaction observer");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
						"Interrupted in Order transaction observer",
						exception);
			}
		}

		record TransactionBlock(
				CountDownLatch entered,
				CountDownLatch release) {
		}
	}

	static class TestOrderSqlStatementInspector implements StatementInspector {

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
		TestOrderObserver testOrderConversionTransactionObserver(
				JdbcTemplate jdbcTemplate) {
			return new TestOrderObserver(jdbcTemplate);
		}

		@Bean
		TestOrderSqlStatementInspector testOrderSqlStatementInspector() {
			return new TestOrderSqlStatementInspector();
		}

		@Bean
		HibernatePropertiesCustomizer testOrderStatementInspectorCustomizer(
				TestOrderSqlStatementInspector inspector) {
			return properties -> properties.put(
					AvailableSettings.STATEMENT_INSPECTOR,
					inspector);
		}

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE)
		ApplicationRunner orderMarkerInitializer(
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
