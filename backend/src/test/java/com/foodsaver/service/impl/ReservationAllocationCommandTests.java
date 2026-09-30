package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.foodsaver.config.ReservationProperties;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.exception.ReservationIdempotencyRaceException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.projection.OfferAllocationTarget;
import com.foodsaver.repository.projection.ReservationAllocationTarget;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReservationAllocationCommandTests {

	private static final Instant TRANSACTION_TIME =
			Instant.parse("2026-09-30T08:00:00Z");
	private static final UUID CUSTOMER_PUBLIC_ID =
			UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OFFER_PUBLIC_ID =
			UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final String IDEMPOTENCY_KEY = "reservation-create-1";
	private static final String REQUEST_HASH = "a".repeat(64);
	private static final BigDecimal ZERO = new BigDecimal("0.000");
	private static final Set<ReservationStatus> ALLOCATED =
			Set.of(ReservationStatus.ACTIVE, ReservationStatus.CONVERTED);

	@Mock
	private CustomerRepository customerRepository;
	@Mock
	private RestaurantRepository restaurantRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private InventoryRepository inventoryRepository;
	@Mock
	private OfferRepository offerRepository;
	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private ReservationAllocationTransactionObserver transactionObserver;

	private ReservationAllocationCommand command;
	private ReservationProperties properties;
	private Customer customer;
	private Restaurant restaurant;
	private Product product;
	private Inventory inventory;
	private Offer offer;
	private OfferAllocationTarget target;

	@BeforeEach
	void setUp() {
		properties = new ReservationProperties();
		properties.setReservationTtl(Duration.ofMinutes(15));
		command = spy(new ReservationAllocationCommand(
				customerRepository,
				restaurantRepository,
				productRepository,
				inventoryRepository,
				offerRepository,
				reservationRepository,
				properties,
				new ReservationResponseMapper(),
				transactionObserver));
		doReturn(TRANSACTION_TIME).when(command).currentTransactionTime();

		customer = customer();
		restaurant = restaurant();
		product = product();
		inventory = inventory();
		offer = offer(50L, inventory, new BigDecimal("5.000"));
		target = new OfferAllocationTarget(50L, 10L, 20L, 30L);

		when(customerRepository.findByPublicIdForAllocation(CUSTOMER_PUBLIC_ID))
				.thenReturn(Optional.of(customer));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				40L,
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.empty());
		when(offerRepository.findAllocationTargetByPublicId(OFFER_PUBLIC_ID))
				.thenReturn(Optional.of(target));
		when(restaurantRepository.findByIdForAllocation(10L))
				.thenReturn(Optional.of(restaurant));
		when(productRepository.findByIdAndRestaurantIdForAllocation(20L, 10L))
				.thenReturn(Optional.of(product));
		when(inventoryRepository.findByIdAndRestaurantId(30L, 10L))
				.thenReturn(Optional.of(inventory));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of());
		when(offerRepository.findAllByIdInOrderByIdForAllocation(List.of(50L)))
				.thenReturn(List.of(offer));
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(ZERO, ZERO, new BigDecimal("2.000"));
		when(reservationRepository.sumQuantityByOfferIdAndStatusIn(
				eq(50L),
				eq(ALLOCATED)))
				.thenReturn(ZERO);
		when(reservationRepository.saveAndFlush(any(Reservation.class)))
				.thenAnswer(invocation -> persisted(invocation.getArgument(0)));
	}

	@Test
	void allocatesInventoryAndPersistsServerOwnedSnapshots() {
		ReservationResponse response = command.allocate(request("2.000"));

		assertEquals(new BigDecimal("6.000"), inventory.getAvailableQuantity());
		assertEquals(new BigDecimal("2.000"), inventory.getReservedQuantity());
		assertEquals(new BigDecimal("10.000"), inventory.getPreparedQuantity());
		assertEquals(new BigDecimal("2.000"), inventory.getSoldQuantity());
		assertEquals(new BigDecimal("80.25"), response.getUnitPrice());
		assertEquals(new BigDecimal("160.50"), response.getTotalAmount());
		assertEquals("INR", response.getCurrencyCode());
		assertEquals(
				TRANSACTION_TIME.plus(Duration.ofMinutes(15)),
				response.getExpiresAt());
		assertEquals(TRANSACTION_TIME, response.getCreatedAt());
	}

	@Test
	void failsClosedWhenInventoryEquationIsBroken() {
		inventory.setPreparedQuantity(new BigDecimal("11.000"));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("2.000")));

		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertEquals(ZERO, inventory.getReservedQuantity());
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void failsClosedWhenReservationLedgerDoesNotMatchInventory() {
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(new BigDecimal("1.000"));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("2.000")));

		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsOfferAndInventoryCapacityConflicts() {
		when(reservationRepository.sumQuantityByOfferIdAndStatusIn(
				eq(50L),
				eq(ALLOCATED)))
				.thenReturn(new BigDecimal("4.000"));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("2.000")));
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void releasesExpiredActiveReservationsBeforeNewAllocation() {
		Reservation expired = reservation(
				60L,
				offer,
				new BigDecimal("2.000"),
				TRANSACTION_TIME);
		inventory.setAvailableQuantity(new BigDecimal("6.000"));
		inventory.setReservedQuantity(new BigDecimal("2.000"));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of(new ReservationAllocationTarget(
						60L,
						50L,
						TRANSACTION_TIME,
						new BigDecimal("2.000"))));
		when(reservationRepository.findAllByIdInOrderByIdForAllocation(
				List.of(60L)))
				.thenReturn(List.of(expired));
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(
						new BigDecimal("2.000"),
						ZERO,
						new BigDecimal("1.000"));

		command.allocate(request("1.000"));

		assertEquals(ReservationStatus.EXPIRED, expired.getStatus());
		assertEquals(TRANSACTION_TIME, expired.getExpiredAt());
		assertEquals(new BigDecimal("7.000"), inventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), inventory.getReservedQuantity());
		verify(reservationRepository).flush();
	}

	@Test
	void locksReferencedOffersAndReservationsInAscendingOrder() {
		Offer otherOffer = offer(49L, inventory, new BigDecimal("2.000"));
		Reservation first = reservation(
				61L,
				otherOffer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME.plusSeconds(100));
		Reservation second = reservation(
				62L,
				offer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME.plusSeconds(100));
		inventory.setAvailableQuantity(new BigDecimal("6.000"));
		inventory.setReservedQuantity(new BigDecimal("2.000"));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of(
						new ReservationAllocationTarget(
								61L, 49L, first.getExpiresAt(), first.getQuantity()),
						new ReservationAllocationTarget(
								62L, 50L, second.getExpiresAt(), second.getQuantity())));
		when(offerRepository.findAllByIdInOrderByIdForAllocation(
				List.of(49L, 50L)))
				.thenReturn(List.of(otherOffer, offer));
		when(reservationRepository.findAllByIdInOrderByIdForAllocation(
				List.of(61L, 62L)))
				.thenReturn(List.of(first, second));
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(
						new BigDecimal("2.000"),
						new BigDecimal("2.000"),
						new BigDecimal("3.000"));

		command.allocate(request("1.000"));

		verify(offerRepository).findAllByIdInOrderByIdForAllocation(
				List.of(49L, 50L));
		verify(reservationRepository).findAllByIdInOrderByIdForAllocation(
				List.of(61L, 62L));
		InOrder lockOrder = inOrder(
				customerRepository,
				restaurantRepository,
				productRepository,
				inventoryRepository,
				offerRepository,
				reservationRepository,
				command);
		lockOrder.verify(customerRepository)
				.findByPublicIdForAllocation(CUSTOMER_PUBLIC_ID);
		lockOrder.verify(restaurantRepository).findByIdForAllocation(10L);
		lockOrder.verify(productRepository)
				.findByIdAndRestaurantIdForAllocation(20L, 10L);
		lockOrder.verify(inventoryRepository)
				.findByIdAndRestaurantId(30L, 10L);
		lockOrder.verify(reservationRepository)
				.findAllocationTargetsByInventoryIdAndStatus(
						30L,
						ReservationStatus.ACTIVE);
		lockOrder.verify(offerRepository)
				.findAllByIdInOrderByIdForAllocation(List.of(49L, 50L));
		lockOrder.verify(reservationRepository)
				.findAllByIdInOrderByIdForAllocation(List.of(61L, 62L));
		lockOrder.verify(command).currentTransactionTime();
	}

	@Test
	void replaysSameIdempotencyHashBeforeExpiryThroughCanonicalLocks() {
		Reservation existing = persisted(reservation(
				70L,
				offer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME.plusSeconds(300)));
		ReflectionTestUtils.setField(existing, "requestHash", REQUEST_HASH);
		inventory.setAvailableQuantity(new BigDecimal("7.000"));
		inventory.setReservedQuantity(new BigDecimal("1.000"));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				40L,
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.of(existing));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of(new ReservationAllocationTarget(
						70L,
						50L,
						existing.getExpiresAt(),
						existing.getQuantity())));
		when(reservationRepository.findAllByIdInOrderByIdForAllocation(
				List.of(70L)))
				.thenReturn(List.of(existing));
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(new BigDecimal("1.000"), new BigDecimal("1.000"));

		ReservationResponse response = command.allocate(request("1.000"));

		assertEquals(existing.getPublicId(), response.getPublicId());
		assertEquals(ReservationStatus.ACTIVE, response.getStatus());
		verify(offerRepository).findAllocationTargetByPublicId(OFFER_PUBLIC_ID);
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void logicallyExpiredReplayReleasesExactlyOnceAndReturnsExpired() {
		Reservation existing = persisted(reservation(
				70L,
				offer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME));
		inventory.setAvailableQuantity(new BigDecimal("7.000"));
		inventory.setReservedQuantity(new BigDecimal("1.000"));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				40L,
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.of(existing));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of(new ReservationAllocationTarget(
						70L,
						50L,
						existing.getExpiresAt(),
						existing.getQuantity())));
		when(reservationRepository.findAllByIdInOrderByIdForAllocation(
				List.of(70L)))
				.thenReturn(List.of(existing));
		when(reservationRepository.sumQuantityByInventoryIdAndStatusIn(
				eq(30L),
				eq(ALLOCATED)))
				.thenReturn(new BigDecimal("1.000"), ZERO);

		ReservationResponse response = command.allocate(request("1.000"));

		assertEquals(ReservationStatus.EXPIRED, response.getStatus());
		assertEquals(TRANSACTION_TIME, existing.getExpiredAt());
		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertEquals(ZERO, inventory.getReservedQuantity());
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void invalidHoldWindowDoesNotExpireExistingReservationsOrAllocate() {
		properties.setReservationTtl(Duration.ZERO);
		Reservation expired = reservation(
				60L,
				offer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME);
		inventory.setAvailableQuantity(new BigDecimal("7.000"));
		inventory.setReservedQuantity(new BigDecimal("1.000"));
		when(reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
				30L,
				ReservationStatus.ACTIVE))
				.thenReturn(List.of(new ReservationAllocationTarget(
						60L,
						50L,
						expired.getExpiresAt(),
						expired.getQuantity())));
		when(reservationRepository.findAllByIdInOrderByIdForAllocation(
				List.of(60L)))
				.thenReturn(List.of(expired));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("1.000")));

		assertEquals(ReservationStatus.ACTIVE, expired.getStatus());
		assertEquals(new BigDecimal("7.000"), inventory.getAvailableQuantity());
		assertEquals(new BigDecimal("1.000"), inventory.getReservedQuantity());
		verify(reservationRepository, never()).flush();
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void subMicrosecondOfferWindowRejectsBeforeAnyMutation() {
		Instant closeExpiry = TRANSACTION_TIME.plusNanos(1);
		offer = offer(
				50L,
				inventory,
				new BigDecimal("5.000"));
		ReflectionTestUtils.setField(offer, "expiresAt", closeExpiry);
		when(offerRepository.findAllByIdInOrderByIdForAllocation(List.of(50L)))
				.thenReturn(List.of(offer));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("2.000")));

		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertEquals(ZERO, inventory.getReservedQuantity());
		verify(reservationRepository, never()).flush();
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void microsecondHoldWindowUsesNormalizedCreationAndExpiry() {
		Instant transactionTime =
				Instant.parse("2026-09-30T08:00:00.123456789Z");
		doReturn(transactionTime).when(command).currentTransactionTime();
		properties.setReservationTtl(Duration.ofNanos(2_000));

		ReservationResponse response = command.allocate(request("2.000"));

		assertEquals(
				Instant.parse("2026-09-30T08:00:00.123456Z"),
				response.getCreatedAt());
		assertEquals(
				Instant.parse("2026-09-30T08:00:00.123458Z"),
				response.getExpiresAt());
	}

	@Test
	void negativeTtlRejectsBeforeAnyInventoryOrExpiryMutation() {
		properties.setReservationTtl(Duration.ofSeconds(-1));

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("1.000")));

		assertEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertEquals(ZERO, inventory.getReservedQuantity());
		verify(reservationRepository, never()).flush();
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsDifferentHashForSameIdempotencyKey() {
		Reservation existing = persisted(reservation(
				70L,
				offer,
				new BigDecimal("1.000"),
				TRANSACTION_TIME.plusSeconds(300)));
		ReflectionTestUtils.setField(existing, "requestHash", "b".repeat(64));
		when(reservationRepository.findByCustomerIdAndIdempotencyKey(
				40L,
				IDEMPOTENCY_KEY))
				.thenReturn(Optional.of(existing));

		assertThrows(
				ReservationIdempotencyConflictException.class,
				() -> command.allocate(request("1.000")));
	}

	@Test
	void translatesOnlyNamedIdempotencyConstraint() {
		ConstraintViolationException constraint =
				org.mockito.Mockito.mock(ConstraintViolationException.class);
		when(constraint.getConstraintName())
				.thenReturn("food_saver_db.uk_reservations_customer_idempotency");
		when(reservationRepository.saveAndFlush(any(Reservation.class)))
				.thenThrow(new DataIntegrityViolationException(
						"duplicate",
						constraint));

		assertThrows(
				ReservationIdempotencyRaceException.class,
				() -> command.allocate(request("2.000")));
	}

	@Test
	void doesNotTranslateUnrelatedIntegrityViolation() {
		DataIntegrityViolationException failure =
				new DataIntegrityViolationException("other constraint");
		when(reservationRepository.saveAndFlush(any(Reservation.class)))
				.thenThrow(failure);

		DataIntegrityViolationException actual = assertThrows(
				DataIntegrityViolationException.class,
				() -> command.allocate(request("2.000")));
		assertSame(failure, actual);
	}

	@Test
	void rejectsLifecycleCurrencyAndRelationshipChangesAfterLocks() {
		product.setCurrencyCode("USD");

		assertThrows(
				ReservationAllocationConflictException.class,
				() -> command.allocate(request("1.000")));
		verify(reservationRepository, never()).saveAndFlush(any());
	}

	private ReservationAllocationCommand.ReservationAllocationRequest request(
			String quantity) {
		return new ReservationAllocationCommand.ReservationAllocationRequest(
				CUSTOMER_PUBLIC_ID,
				OFFER_PUBLIC_ID,
				new BigDecimal(quantity),
				IDEMPOTENCY_KEY,
				REQUEST_HASH);
	}

	private Customer customer() {
		Customer value = new Customer();
		ReflectionTestUtils.setField(value, "id", 40L);
		ReflectionTestUtils.setField(value, "publicId", CUSTOMER_PUBLIC_ID);
		value.setStatus(CustomerStatus.ACTIVE);
		return value;
	}

	private Restaurant restaurant() {
		Restaurant value = new Restaurant();
		ReflectionTestUtils.setField(value, "id", 10L);
		ReflectionTestUtils.setField(value, "publicId", UUID.randomUUID());
		value.setStatus(RestaurantStatus.ACTIVE);
		value.setCurrencyCode("INR");
		return value;
	}

	private Product product() {
		Product value = new Product();
		ReflectionTestUtils.setField(value, "id", 20L);
		ReflectionTestUtils.setField(value, "publicId", UUID.randomUUID());
		value.setRestaurant(restaurant);
		value.setStatus(ProductStatus.ACTIVE);
		value.setCurrencyCode("INR");
		return value;
	}

	private Inventory inventory() {
		Inventory value = new Inventory();
		ReflectionTestUtils.setField(value, "id", 30L);
		ReflectionTestUtils.setField(value, "publicId", UUID.randomUUID());
		value.setRestaurant(restaurant);
		value.setProduct(product);
		value.setPreparedQuantity(new BigDecimal("10.000"));
		value.setAvailableQuantity(new BigDecimal("8.000"));
		value.setReservedQuantity(ZERO);
		value.setSoldQuantity(new BigDecimal("2.000"));
		value.setStatus(InventoryStatus.ACTIVE);
		return value;
	}

	private Offer offer(
			Long id,
			Inventory sourceInventory,
			BigDecimal offeredQuantity) {
		FoodEligibilityEvaluation evaluation = new FoodEligibilityEvaluation(
				null,
				"POLICY",
				"1",
				"SOURCE",
				FoodEligibilityStatus.ELIGIBLE_FOR_OFFER,
				0L,
				sourceInventory.getAvailableQuantity(),
				TRANSACTION_TIME.minusSeconds(100));
		Offer value = new Offer(
				restaurant,
				product,
				sourceInventory,
				evaluation,
				new BigDecimal("100.00"),
				new BigDecimal("19.75"),
				new BigDecimal("80.25"),
				"INR",
				offeredQuantity,
				TRANSACTION_TIME.minusSeconds(60),
				TRANSACTION_TIME.plusSeconds(3600));
		ReflectionTestUtils.setField(value, "id", id);
		ReflectionTestUtils.setField(value, "publicId", OFFER_PUBLIC_ID);
		value.setStatus(OfferStatus.ACTIVE);
		return value;
	}

	private Reservation reservation(
			Long id,
			Offer reservationOffer,
			BigDecimal quantity,
			Instant expiresAt) {
		Reservation value = new Reservation(
				customer,
				restaurant,
				reservationOffer,
				inventory,
				quantity,
				new BigDecimal("80.25"),
				quantity.multiply(new BigDecimal("80.25")),
				"INR",
				expiresAt,
				"existing-" + id,
				REQUEST_HASH);
		ReflectionTestUtils.setField(value, "id", id);
		value.setStatus(ReservationStatus.ACTIVE);
		return value;
	}

	private Reservation persisted(Reservation reservation) {
		ReflectionTestUtils.setField(reservation, "publicId", UUID.randomUUID());
		if (reservation.getCreatedAt() == null) {
			ReflectionTestUtils.setField(
					reservation,
					"createdAt",
					TRANSACTION_TIME.minusSeconds(10));
		}
		if (reservation.getUpdatedAt() == null) {
			ReflectionTestUtils.setField(
					reservation,
					"updatedAt",
					TRANSACTION_TIME.minusSeconds(10));
		}
		return reservation;
	}
}
