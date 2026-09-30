package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.util.ReflectionTestUtils;

import com.foodsaver.config.ReservationProperties;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.OfferNotFoundException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.exception.ReservationValidationException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ReservationRepository;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTests {

	private static final UUID CUSTOMER_PUBLIC_ID =
			UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID RESTAURANT_PUBLIC_ID =
			UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID PRODUCT_PUBLIC_ID =
			UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID INVENTORY_PUBLIC_ID =
			UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID OFFER_PUBLIC_ID =
			UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID RESERVATION_PUBLIC_ID =
			UUID.fromString("66666666-6666-6666-6666-666666666666");
	private static final Instant TRANSACTION_TIME =
			Instant.parse("2026-09-30T08:00:00Z");
	private static final Duration RESERVATION_TTL = Duration.ofMinutes(15);
	private static final String IDEMPOTENCY_KEY = "reservation-create-1";

	@Mock
	private CustomerRepository customerRepository;

	@Mock
	private OfferRepository offerRepository;

	@Mock
	private ReservationRepository reservationRepository;

	private ReservationProperties reservationProperties;
	private ReservationServiceImpl service;
	private Customer customer;
	private Restaurant restaurant;
	private Product product;
	private Inventory inventory;
	private Offer offer;

	@BeforeEach
	void setUp() {
		reservationProperties = new ReservationProperties();
		reservationProperties.setReservationTtl(RESERVATION_TTL);
		service = spy(new ReservationServiceImpl(
				customerRepository,
				offerRepository,
				reservationRepository,
				reservationProperties));
		lenient().doReturn(TRANSACTION_TIME).when(service).currentTransactionTime();

		customer = customer(CustomerStatus.ACTIVE);
		restaurant = restaurant(RestaurantStatus.ACTIVE, "INR");
		product = product(restaurant, ProductStatus.ACTIVE, "INR");
		inventory = inventory(restaurant, product, InventoryStatus.ACTIVE);
		offer = offer(
				restaurant,
				product,
				inventory,
				OfferStatus.ACTIVE,
				new BigDecimal("80.25"),
				"INR",
				new BigDecimal("5.000"),
				TRANSACTION_TIME.minusSeconds(60),
				TRANSACTION_TIME.plusSeconds(3600));

		lenient().when(customerRepository.findByPublicId(CUSTOMER_PUBLIC_ID))
				.thenReturn(Optional.of(customer));
		lenient().when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				customer.getId(),
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.empty());
		lenient().when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(offer));
		lenient().when(reservationRepository.save(any(Reservation.class)))
				.thenAnswer(invocation -> persisted(invocation.getArgument(0)));
	}

	@Test
	void rejectsMissingCustomer() {
		when(customerRepository.findByPublicId(CUSTOMER_PUBLIC_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				CustomerNotFoundException.class,
				() -> create(new BigDecimal("1.000")));

		verify(offerRepository, never()).findByPublicId(any());
		verify(reservationRepository, never()).save(any());
	}

	@Test
	void rejectsInactiveCustomer() {
		customer.setStatus(CustomerStatus.SUSPENDED);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));

		verify(offerRepository, never()).findByPublicId(any());
	}

	@Test
	void rejectsMissingOffer() {
		when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				OfferNotFoundException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsInactiveOffer() {
		offer.setStatus(OfferStatus.CLOSED);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsOfferThatHasNotStarted() {
		offer = offer(
				restaurant,
				product,
				inventory,
				OfferStatus.ACTIVE,
				new BigDecimal("80.25"),
				"INR",
				new BigDecimal("5.000"),
				TRANSACTION_TIME.plusSeconds(1),
				TRANSACTION_TIME.plusSeconds(3600));
		when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(offer));

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsLogicallyExpiredOffer() {
		offer = offer(
				restaurant,
				product,
				inventory,
				OfferStatus.ACTIVE,
				new BigDecimal("80.25"),
				"INR",
				new BigDecimal("5.000"),
				TRANSACTION_TIME.minusSeconds(3600),
				TRANSACTION_TIME);
		when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(offer));

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsInvalidQuantityAndDatabaseIncompatiblePrecision() {
		assertThrows(
				ReservationValidationException.class,
				() -> create(BigDecimal.ZERO));
		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.0001")));
		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1000000000.000")));
	}

	@Test
	void rejectsQuantityGreaterThanOfferQuantity() {
		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("5.001")));
	}

	@Test
	void rejectsInactiveInventory() {
		inventory.setStatus(InventoryStatus.CLOSED);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsInactiveProduct() {
		product.setStatus(ProductStatus.INACTIVE);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsInactiveRestaurant() {
		restaurant.setStatus(RestaurantStatus.SUSPENDED);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsRelationshipMismatch() {
		Product differentProduct = product(
				restaurant,
				ProductStatus.ACTIVE,
				"INR");
		ReflectionTestUtils.setField(differentProduct, "id", 99L);
		inventory.setProduct(differentProduct);

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void rejectsCurrencyMismatch() {
		product.setCurrencyCode("USD");

		assertThrows(
				ReservationValidationException.class,
				() -> create(new BigDecimal("1.000")));
	}

	@Test
	void snapshotsOfferPriceAndCalculatesTotalWithHalfUpRounding() {
		offer = offer(
				restaurant,
				product,
				inventory,
				OfferStatus.ACTIVE,
				new BigDecimal("1.00"),
				"INR",
				new BigDecimal("5.000"),
				TRANSACTION_TIME.minusSeconds(60),
				TRANSACTION_TIME.plusSeconds(3600));
		when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(offer));

		ReservationResponse response = create(new BigDecimal("1.005"));
		Reservation saved = captureSavedReservation();

		assertEquals(new BigDecimal("1.00"), response.getUnitPrice());
		assertEquals(new BigDecimal("1.01"), response.getTotalAmount());
		assertEquals(new BigDecimal("1.00"), saved.getUnitPrice());
		assertEquals(new BigDecimal("1.01"), saved.getTotalAmount());
	}

	@Test
	void usesConfiguredTtlForReservationExpiry() {
		ReservationResponse response = create(new BigDecimal("1.000"));

		assertEquals(
				TRANSACTION_TIME.plus(RESERVATION_TTL),
				response.getExpiresAt());
	}

	@Test
	void capsReservationExpiryAtOfferExpiry() {
		Instant offerExpiry = TRANSACTION_TIME.plusSeconds(60);
		offer = offer(
				restaurant,
				product,
				inventory,
				OfferStatus.ACTIVE,
				new BigDecimal("80.25"),
				"INR",
				new BigDecimal("5.000"),
				TRANSACTION_TIME.minusSeconds(60),
				offerExpiry);
		when(offerRepository.findByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(offer));

		ReservationResponse response = create(new BigDecimal("1.000"));

		assertEquals(offerExpiry, response.getExpiresAt());
	}

	@Test
	void startsReservationActiveAndMapsOnlyApprovedResponseFields() {
		ReservationResponse response = create(new BigDecimal("2.125"));

		assertEquals(RESERVATION_PUBLIC_ID, response.getPublicId());
		assertEquals(CUSTOMER_PUBLIC_ID, response.getCustomerPublicId());
		assertEquals(RESTAURANT_PUBLIC_ID, response.getRestaurantPublicId());
		assertEquals(OFFER_PUBLIC_ID, response.getOfferPublicId());
		assertEquals(INVENTORY_PUBLIC_ID, response.getInventoryPublicId());
		assertEquals(ReservationStatus.ACTIVE, response.getStatus());
		assertEquals("INR", response.getCurrencyCode());
		assertEquals(
				Instant.parse("2026-09-30T08:00:01Z"),
				response.getCreatedAt());
		assertEquals(
				Instant.parse("2026-09-30T08:00:01Z"),
				response.getUpdatedAt());
		assertArrayEquals(
				new String[] {
						"createdAt",
						"currencyCode",
						"customerPublicId",
						"expiresAt",
						"inventoryPublicId",
						"offerPublicId",
						"publicId",
						"quantity",
						"restaurantPublicId",
						"status",
						"totalAmount",
						"unitPrice",
						"updatedAt"
				},
				Arrays.stream(ReservationResponse.class.getDeclaredFields())
						.map(Field::getName)
						.sorted()
						.toArray(String[]::new));
	}

	@Test
	void computesDeterministicSha256HashFromCanonicalBusinessInput()
			throws Exception {
		create(new BigDecimal("1.0"));
		Reservation saved = captureSavedReservation();
		String expectedInput = CUSTOMER_PUBLIC_ID
				+ "\n"
				+ OFFER_PUBLIC_ID
				+ "\n"
				+ "1.000";
		String expectedHash = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256")
						.digest(expectedInput.getBytes(StandardCharsets.UTF_8)));

		assertEquals(expectedHash, saved.getRequestHash());
		assertEquals(64, saved.getRequestHash().length());
	}

	@Test
	void sameIdempotencyKeyAndHashReturnsExistingReservation() throws Exception {
		BigDecimal quantity = new BigDecimal("1.000");
		String requestHash = requestHash(quantity);
		Reservation existing = persisted(new Reservation(
				customer,
				restaurant,
				offer,
				inventory,
				quantity,
				offer.getOfferPrice(),
				new BigDecimal("80.25"),
				"INR",
				TRANSACTION_TIME.plus(RESERVATION_TTL),
				IDEMPOTENCY_KEY,
				requestHash));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				customer.getId(),
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.of(existing));

		ReservationResponse response = create(quantity);

		assertEquals(RESERVATION_PUBLIC_ID, response.getPublicId());
		verify(offerRepository, never()).findByPublicId(any());
		verify(reservationRepository, never()).save(any());
	}

	@Test
	void sameIdempotencyKeyWithDifferentHashThrowsConflict() {
		Reservation existing = persisted(new Reservation(
				customer,
				restaurant,
				offer,
				inventory,
				new BigDecimal("1.000"),
				offer.getOfferPrice(),
				new BigDecimal("80.25"),
				"INR",
				TRANSACTION_TIME.plus(RESERVATION_TTL),
				IDEMPOTENCY_KEY,
				"different-request-hash"));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				customer.getId(),
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.of(existing));

		assertThrows(
				ReservationIdempotencyConflictException.class,
				() -> create(new BigDecimal("1.000")));

		verify(offerRepository, never()).findByPublicId(any());
		verify(reservationRepository, never()).save(any());
	}

	@Test
	void rejectsMissingOrOverlengthIdempotencyKey() {
		ReservationCreateRequest request = request(new BigDecimal("1.000"));

		assertThrows(
				ReservationValidationException.class,
				() -> service.createReservation(CUSTOMER_PUBLIC_ID, request, " "));
		assertThrows(
				ReservationValidationException.class,
				() -> service.createReservation(
						CUSTOMER_PUBLIC_ID,
						request,
						"x".repeat(101)));
	}

	@Test
	void leavesInventoryQuantitiesUnchanged() {
		inventory.setPreparedQuantity(new BigDecimal("10.000"));
		inventory.setAvailableQuantity(new BigDecimal("8.000"));
		inventory.setReservedQuantity(new BigDecimal("1.000"));
		inventory.setSoldQuantity(new BigDecimal("1.000"));

		create(new BigDecimal("2.000"));

		assertEquals(new BigDecimal("10.000"), inventory.getPreparedQuantity());
		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), inventory.getReservedQuantity());
		assertEquals(new BigDecimal("1.000"), inventory.getSoldQuantity());
	}

	@Test
	void publicOfferLookupDoesNotUsePessimisticLocking() throws Exception {
		Lock lock = OfferRepository.class
				.getMethod("findByPublicId", UUID.class)
				.getAnnotation(Lock.class);

		assertNull(lock);
		create(new BigDecimal("1.000"));
		verify(offerRepository).findByPublicId(OFFER_PUBLIC_ID);
	}

	private ReservationResponse create(BigDecimal quantity) {
		return service.createReservation(
				CUSTOMER_PUBLIC_ID,
				request(quantity),
				IDEMPOTENCY_KEY);
	}

	private ReservationCreateRequest request(BigDecimal quantity) {
		ReservationCreateRequest request = new ReservationCreateRequest();
		request.setOfferPublicId(OFFER_PUBLIC_ID);
		request.setQuantity(quantity);
		return request;
	}

	private Customer customer(CustomerStatus status) {
		Customer value = new Customer();
		ReflectionTestUtils.setField(value, "id", 40L);
		ReflectionTestUtils.setField(value, "publicId", CUSTOMER_PUBLIC_ID);
		value.setStatus(status);
		return value;
	}

	private Restaurant restaurant(
			RestaurantStatus status,
			String currencyCode) {
		Restaurant value = new Restaurant();
		ReflectionTestUtils.setField(value, "id", 10L);
		ReflectionTestUtils.setField(value, "publicId", RESTAURANT_PUBLIC_ID);
		value.setStatus(status);
		value.setCurrencyCode(currencyCode);
		return value;
	}

	private Product product(
			Restaurant owner,
			ProductStatus status,
			String currencyCode) {
		Product value = new Product();
		ReflectionTestUtils.setField(value, "id", 20L);
		ReflectionTestUtils.setField(value, "publicId", PRODUCT_PUBLIC_ID);
		value.setRestaurant(owner);
		value.setStatus(status);
		value.setCurrencyCode(currencyCode);
		return value;
	}

	private Inventory inventory(
			Restaurant owner,
			Product inventoryProduct,
			InventoryStatus status) {
		Inventory value = new Inventory();
		ReflectionTestUtils.setField(value, "id", 30L);
		ReflectionTestUtils.setField(value, "publicId", INVENTORY_PUBLIC_ID);
		value.setRestaurant(owner);
		value.setProduct(inventoryProduct);
		value.setStatus(status);
		return value;
	}

	private Offer offer(
			Restaurant offerRestaurant,
			Product offerProduct,
			Inventory offerInventory,
			OfferStatus status,
			BigDecimal offerPrice,
			String currencyCode,
			BigDecimal offeredQuantity,
			Instant startAt,
			Instant expiresAt) {
		Offer value = new Offer(
				offerRestaurant,
				offerProduct,
				offerInventory,
				null,
				new BigDecimal("100.00"),
				new BigDecimal("19.75"),
				offerPrice,
				currencyCode,
				offeredQuantity,
				startAt,
				expiresAt);
		ReflectionTestUtils.setField(value, "id", 50L);
		ReflectionTestUtils.setField(value, "publicId", OFFER_PUBLIC_ID);
		value.setStatus(status);
		return value;
	}

	private Reservation persisted(Reservation reservation) {
		ReflectionTestUtils.setField(
				reservation,
				"publicId",
				RESERVATION_PUBLIC_ID);
		ReflectionTestUtils.setField(
				reservation,
				"createdAt",
				Instant.parse("2026-09-30T08:00:01Z"));
		ReflectionTestUtils.setField(
				reservation,
				"updatedAt",
				Instant.parse("2026-09-30T08:00:01Z"));
		ReflectionTestUtils.setField(reservation, "version", 0L);
		return reservation;
	}

	private Reservation captureSavedReservation() {
		ArgumentCaptor<Reservation> captor =
				ArgumentCaptor.forClass(Reservation.class);
		verify(reservationRepository).save(captor.capture());
		return captor.getValue();
	}

	private String requestHash(BigDecimal quantity) throws Exception {
		String canonicalInput = CUSTOMER_PUBLIC_ID
				+ "\n"
				+ OFFER_PUBLIC_ID
				+ "\n"
				+ quantity.setScale(3).toPlainString();
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256")
						.digest(canonicalInput.getBytes(StandardCharsets.UTF_8)));
	}
}
