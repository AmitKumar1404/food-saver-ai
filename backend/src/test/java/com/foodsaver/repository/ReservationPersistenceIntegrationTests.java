package com.foodsaver.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.entity.Customer;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
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

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReservationPersistenceIntegrationTests {

	private static final BigDecimal QUANTITY = new BigDecimal("2.125");
	private static final BigDecimal UNIT_PRICE = new BigDecimal("80.25");
	private static final BigDecimal TOTAL_AMOUNT = new BigDecimal("170.53");
	private static final String CURRENCY_CODE = "INR";
	private static final String REQUEST_HASH = "a".repeat(64);

	@Autowired
	private ReservationRepository reservationRepository;

	@Autowired
	private CustomerRepository customerRepository;

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
	private EntityManager entityManager;

	@Test
	void persistsReservationWithReferencesSnapshotsAndSystemFields() {
		DomainFixture fixture = createFixture();
		String idempotencyKey = idempotencyKey();
		Reservation saved = reservationRepository.saveAndFlush(
				reservation(
						fixture,
						QUANTITY,
						UNIT_PRICE,
						TOTAL_AMOUNT,
						Instant.now().plusSeconds(3600),
						idempotencyKey));
		Long reservationId = saved.getId();
		UUID publicId = saved.getPublicId();
		entityManager.clear();

		Reservation persisted = reservationRepository.findByPublicId(publicId)
				.orElseThrow();

		assertNotNull(reservationId);
		assertEquals(fixture.customer().getId(), persisted.getCustomer().getId());
		assertEquals(fixture.restaurant().getId(), persisted.getRestaurant().getId());
		assertEquals(fixture.offer().getId(), persisted.getOffer().getId());
		assertEquals(fixture.inventory().getId(), persisted.getInventory().getId());
		assertEquals(QUANTITY, persisted.getQuantity());
		assertEquals(UNIT_PRICE, persisted.getUnitPrice());
		assertEquals(TOTAL_AMOUNT, persisted.getTotalAmount());
		assertEquals(CURRENCY_CODE, persisted.getCurrencyCode());
		assertEquals(ReservationStatus.ACTIVE, persisted.getStatus());
		assertEquals(idempotencyKey, persisted.getIdempotencyKey());
		assertEquals(REQUEST_HASH, persisted.getRequestHash());
		assertNotNull(persisted.getVersion());
		assertNotNull(persisted.getCreatedAt());
		assertNotNull(persisted.getUpdatedAt());
		assertNull(persisted.getCancelledAt());
		assertNull(persisted.getExpiredAt());
		assertNull(persisted.getConvertedAt());
		assertEquals(
				"ACTIVE",
				entityManager.createNativeQuery(
								"select status from reservations where id = :id",
								String.class)
						.setParameter("id", reservationId)
						.getSingleResult());
		assertEquals(
				publicId,
				reservationRepository
						.findByPublicIdAndCustomerId(publicId, fixture.customer().getId())
						.orElseThrow()
						.getPublicId());
		assertTrue(reservationRepository.existsByCustomerIdAndIdempotencyKey(
				fixture.customer().getId(),
				idempotencyKey));
	}

	@Test
	void generatesUniquePublicIds() {
		DomainFixture fixture = createFixture();
		Reservation first = reservationRepository.saveAndFlush(
				validReservation(fixture));
		Reservation second = reservationRepository.saveAndFlush(
				reservation(
						fixture,
						QUANTITY,
						UNIT_PRICE,
						TOTAL_AMOUNT,
						Instant.now().plusSeconds(3600),
						idempotencyKey()));

		assertNotNull(first.getPublicId());
		assertNotNull(second.getPublicId());
		assertNotEquals(first.getPublicId(), second.getPublicId());
	}

	@Test
	void rejectsDuplicatePublicId() {
		DomainFixture fixture = createFixture();
		Reservation first = reservationRepository.saveAndFlush(
				validReservation(fixture));
		Reservation duplicate = reservation(
				fixture,
				QUANTITY,
				UNIT_PRICE,
				TOTAL_AMOUNT,
				Instant.now().plusSeconds(3600),
				idempotencyKey());
		ReflectionTestUtils.setField(duplicate, "publicId", first.getPublicId());

		assertThrows(
				DataIntegrityViolationException.class,
				() -> reservationRepository.saveAndFlush(duplicate));
	}

	@Test
	void rejectsDuplicateCustomerIdempotencyKey() {
		DomainFixture fixture = createFixture();
		String idempotencyKey = idempotencyKey();
		reservationRepository.saveAndFlush(reservation(
				fixture,
				QUANTITY,
				UNIT_PRICE,
				TOTAL_AMOUNT,
				Instant.now().plusSeconds(3600),
				idempotencyKey));

		assertThrows(
				DataIntegrityViolationException.class,
				() -> reservationRepository.saveAndFlush(reservation(
						fixture,
						QUANTITY,
						UNIT_PRICE,
						TOTAL_AMOUNT,
						Instant.now().plusSeconds(3600),
						idempotencyKey)));
	}

	@Test
	void rejectsNonPositiveQuantity() {
		assertCheckConstraintViolation(
				BigDecimal.ZERO,
				UNIT_PRICE,
				TOTAL_AMOUNT,
				Instant.now().plusSeconds(3600));
	}

	@Test
	void rejectsNonPositiveUnitPrice() {
		assertCheckConstraintViolation(
				QUANTITY,
				BigDecimal.ZERO,
				TOTAL_AMOUNT,
				Instant.now().plusSeconds(3600));
	}

	@Test
	void rejectsNonPositiveTotalAmount() {
		assertCheckConstraintViolation(
				QUANTITY,
				UNIT_PRICE,
				BigDecimal.ZERO,
				Instant.now().plusSeconds(3600));
	}

	@Test
	void rejectsExpiryThatIsNotAfterCreation() {
		assertCheckConstraintViolation(
				QUANTITY,
				UNIT_PRICE,
				TOTAL_AMOUNT,
				Instant.EPOCH);
	}

	private void assertCheckConstraintViolation(
			BigDecimal quantity,
			BigDecimal unitPrice,
			BigDecimal totalAmount,
			Instant expiresAt) {
		DomainFixture fixture = createFixture();
		Reservation reservation = reservation(
				fixture,
				quantity,
				unitPrice,
				totalAmount,
				expiresAt,
				idempotencyKey());

		assertThrows(
				DataIntegrityViolationException.class,
				() -> reservationRepository.saveAndFlush(reservation));
	}

	private Reservation validReservation(DomainFixture fixture) {
		return reservation(
				fixture,
				QUANTITY,
				UNIT_PRICE,
				TOTAL_AMOUNT,
				Instant.now().plusSeconds(3600),
				idempotencyKey());
	}

	private Reservation reservation(
			DomainFixture fixture,
			BigDecimal quantity,
			BigDecimal unitPrice,
			BigDecimal totalAmount,
			Instant expiresAt,
			String idempotencyKey) {
		return new Reservation(
				fixture.customer(),
				fixture.restaurant(),
				fixture.offer(),
				fixture.inventory(),
				quantity,
				unitPrice,
				totalAmount,
				CURRENCY_CODE,
				expiresAt,
				idempotencyKey,
				REQUEST_HASH);
	}

	private String idempotencyKey() {
		return "reservation-" + UUID.randomUUID();
	}

	private DomainFixture createFixture() {
		Restaurant restaurant = new Restaurant();
		restaurant.setName("Reservation Persistence Restaurant");
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setContactEmail("reservation-" + UUID.randomUUID() + "@example.com");
		restaurant.setContactPhone("+910000000000");
		restaurant.setAddressLine1("Integration Test Address");
		restaurant.setCity("Test City");
		restaurant.setStateProvince("Test State");
		restaurant.setPostalCode("000000");
		restaurant.setCountryCode("IN");
		restaurant.setTimezone("Asia/Kolkata");
		restaurant.setCurrencyCode(CURRENCY_CODE);
		restaurant.setStatus(RestaurantStatus.ACTIVE);
		restaurant = restaurantRepository.saveAndFlush(restaurant);

		Product product = new Product();
		product.setRestaurant(restaurant);
		product.setName("Reservation Persistence Product");
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setBasePrice(new BigDecimal("100.00"));
		product.setCurrencyCode(CURRENCY_CODE);
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

		Instant offerStart = Instant.now();
		Offer offer = offerRepository.saveAndFlush(new Offer(
				restaurant,
				product,
				inventory,
				evaluation,
				new BigDecimal("100.00"),
				new BigDecimal("19.75"),
				UNIT_PRICE,
				CURRENCY_CODE,
				new BigDecimal("5.000"),
				offerStart,
				offerStart.plusSeconds(7200)));

		Customer customer = new Customer();
		customer.setEmail("customer-" + UUID.randomUUID() + "@example.com");
		customer.setDisplayName("Reservation Customer");
		customer = customerRepository.saveAndFlush(customer);

		return new DomainFixture(customer, restaurant, inventory, offer);
	}

	private record DomainFixture(
			Customer customer,
			Restaurant restaurant,
			Inventory inventory,
			Offer offer) {
	}
}
