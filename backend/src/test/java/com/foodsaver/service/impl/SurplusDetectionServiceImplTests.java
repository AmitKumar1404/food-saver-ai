package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.foodsaver.config.SurplusDetectionProperties;
import com.foodsaver.dto.response.SurplusDetectionResponse;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.InventoryNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;

@ExtendWith(MockitoExtension.class)
class SurplusDetectionServiceImplTests {

	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final Long RESTAURANT_ID = 1L;
	private static final Long OTHER_RESTAURANT_ID = 2L;
	private static final BigDecimal THRESHOLD = new BigDecimal("5.000");

	@Mock
	private SurplusDetectionRepository surplusDetectionRepository;

	@Mock
	private InventoryRepository inventoryRepository;

	@Mock
	private RestaurantRepository restaurantRepository;

	private SurplusDetectionProperties surplusDetectionProperties;
	private SurplusDetectionServiceImpl service;

	@BeforeEach
	void setUp() {
		surplusDetectionProperties = new SurplusDetectionProperties();
		surplusDetectionProperties.setThresholdQuantity(THRESHOLD);
		service = new SurplusDetectionServiceImpl(
				surplusDetectionRepository,
				inventoryRepository,
				restaurantRepository,
				surplusDetectionProperties);
	}

	@Test
	void createsNotSurplusDetectionWhenAvailableQuantityIsBelowThreshold() {
		Inventory inventory = arrangeOwnedInventory(new BigDecimal("4.999"));

		SurplusDetectionResponse response = service.createDetection(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID);
		SurplusDetection savedDetection = captureSavedDetection();

		assertEquals(SurplusDetectionStatus.NOT_SURPLUS, response.getStatus());
		assertBigDecimalEquals(BigDecimal.ZERO, response.getDetectedQuantity());
		assertBigDecimalEquals(BigDecimal.ZERO, savedDetection.getDetectedQuantity());
		assertBigDecimalEquals(
				inventory.getAvailableQuantity(),
				new BigDecimal("4.999"));
	}

	@Test
	void createsPotentialSurplusDetectionWhenAvailableQuantityEqualsThreshold() {
		arrangeOwnedInventory(new BigDecimal("5.000"));

		SurplusDetectionResponse response = service.createDetection(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID);
		SurplusDetection savedDetection = captureSavedDetection();

		assertEquals(SurplusDetectionStatus.POTENTIAL_SURPLUS, response.getStatus());
		assertBigDecimalEquals(new BigDecimal("5.000"), response.getDetectedQuantity());
		assertBigDecimalEquals(
				new BigDecimal("5.000"),
				savedDetection.getDetectedQuantity());
	}

	@Test
	void createsPotentialSurplusDetectionWhenAvailableQuantityExceedsThreshold() {
		arrangeOwnedInventory(new BigDecimal("5.001"));

		SurplusDetectionResponse response = service.createDetection(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID);

		assertEquals(SurplusDetectionStatus.POTENTIAL_SURPLUS, response.getStatus());
		assertBigDecimalEquals(new BigDecimal("5.001"), response.getDetectedQuantity());
	}

	@Test
	void persistsExactConfiguredThreshold() {
		arrangeOwnedInventory(new BigDecimal("8.000"));

		service.createDetection(RESTAURANT_PUBLIC_ID, INVENTORY_PUBLIC_ID);

		SurplusDetection detection = captureSavedDetection();
		assertBigDecimalEquals(THRESHOLD, detection.getThresholdQuantity());
	}

	@Test
	void doesNotModifyInventoryQuantities() {
		Inventory inventory = arrangeOwnedInventory(new BigDecimal("8.000"));
		inventory.setPreparedQuantity(new BigDecimal("10.000"));
		inventory.setReservedQuantity(new BigDecimal("1.000"));
		inventory.setSoldQuantity(new BigDecimal("1.000"));

		service.createDetection(RESTAURANT_PUBLIC_ID, INVENTORY_PUBLIC_ID);

		assertBigDecimalEquals(new BigDecimal("10.000"), inventory.getPreparedQuantity());
		assertBigDecimalEquals(new BigDecimal("8.000"), inventory.getAvailableQuantity());
		assertBigDecimalEquals(new BigDecimal("1.000"), inventory.getReservedQuantity());
		assertBigDecimalEquals(new BigDecimal("1.000"), inventory.getSoldQuantity());
	}

	@Test
	void persistsDetectionSnapshot() {
		arrangeOwnedInventory(new BigDecimal("8.000"));

		service.createDetection(RESTAURANT_PUBLIC_ID, INVENTORY_PUBLIC_ID);

		verify(surplusDetectionRepository).save(any(SurplusDetection.class));
	}

	@Test
	void allowsMultipleDetectionSnapshotsForSameInventory() {
		arrangeOwnedInventory(new BigDecimal("8.000"));

		service.createDetection(RESTAURANT_PUBLIC_ID, INVENTORY_PUBLIC_ID);
		service.createDetection(RESTAURANT_PUBLIC_ID, INVENTORY_PUBLIC_ID);

		verify(surplusDetectionRepository, times(2)).save(any(SurplusDetection.class));
	}

	@Test
	void returnsOwnershipValidatedHistoryInRepositoryOrder() {
		Restaurant restaurant = arrangeRestaurant();
		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setAvailableQuantity(new BigDecimal("8.000"));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(inventory));
		SurplusDetection newest = detection(
				inventory,
				SurplusDetectionStatus.POTENTIAL_SURPLUS,
				new BigDecimal("8.000"));
		SurplusDetection oldest = detection(
				inventory,
				SurplusDetectionStatus.NOT_SURPLUS,
				BigDecimal.ZERO);
		when(surplusDetectionRepository
				.findByInventoryPublicIdOrderByDetectedAtDesc(INVENTORY_PUBLIC_ID))
				.thenReturn(List.of(newest, oldest));

		List<SurplusDetectionResponse> responses = service.getDetectionHistory(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID);

		assertEquals(2, responses.size());
		assertEquals(SurplusDetectionStatus.POTENTIAL_SURPLUS, responses.get(0).getStatus());
		assertEquals(SurplusDetectionStatus.NOT_SURPLUS, responses.get(1).getStatus());
	}

	@Test
	void rejectsMissingRestaurant() {
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				RestaurantNotFoundException.class,
				() -> service.createDetection(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID));
		verify(surplusDetectionRepository, never()).save(any());
	}

	@Test
	void rejectsMissingInventory() {
		arrangeRestaurant();
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				InventoryNotFoundException.class,
				() -> service.createDetection(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID));
		verify(surplusDetectionRepository, never()).save(any());
	}

	@Test
	void rejectsInventoryOwnedByAnotherRestaurant() {
		arrangeRestaurant();
		Restaurant otherRestaurant = new Restaurant();
		ReflectionTestUtils.setField(otherRestaurant, "id", OTHER_RESTAURANT_ID);
		Inventory inventoryOwnedByOtherRestaurant = new Inventory();
		ReflectionTestUtils.setField(
				inventoryOwnedByOtherRestaurant,
				"publicId",
				INVENTORY_PUBLIC_ID);
		inventoryOwnedByOtherRestaurant.setRestaurant(otherRestaurant);

		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenAnswer(invocation -> {
					UUID requestedInventoryPublicId = invocation.getArgument(0);
					Long requestedRestaurantId = invocation.getArgument(1);
					boolean inventoryMatches =
							inventoryOwnedByOtherRestaurant.getPublicId()
									.equals(requestedInventoryPublicId);
					boolean ownerMatches =
							inventoryOwnedByOtherRestaurant.getRestaurant().getId()
									.equals(requestedRestaurantId);
					return inventoryMatches && ownerMatches
							? Optional.of(inventoryOwnedByOtherRestaurant)
							: Optional.empty();
				});

		assertThrows(
				InventoryNotFoundException.class,
				() -> service.createDetection(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID));
		verify(inventoryRepository).findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID);
		verify(surplusDetectionRepository, never()).save(any());
	}

	private Inventory arrangeOwnedInventory(BigDecimal availableQuantity) {
		Restaurant restaurant = arrangeRestaurant();
		Inventory inventory = new Inventory();
		inventory.setRestaurant(restaurant);
		inventory.setAvailableQuantity(availableQuantity);

		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				restaurant.getId()))
				.thenReturn(Optional.of(inventory));
		when(surplusDetectionRepository.save(any(SurplusDetection.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		return inventory;
	}

	private Restaurant arrangeRestaurant() {
		Restaurant restaurant = new Restaurant();
		ReflectionTestUtils.setField(restaurant, "id", RESTAURANT_ID);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(restaurant));
		return restaurant;
	}

	private SurplusDetection detection(
			Inventory inventory,
			SurplusDetectionStatus status,
			BigDecimal detectedQuantity) {
		SurplusDetection detection = new SurplusDetection();
		detection.setInventory(inventory);
		detection.setStatus(status);
		detection.setDetectedQuantity(detectedQuantity);
		detection.setThresholdQuantity(THRESHOLD);
		return detection;
	}

	private SurplusDetection captureSavedDetection() {
		ArgumentCaptor<SurplusDetection> captor =
				ArgumentCaptor.forClass(SurplusDetection.class);
		verify(surplusDetectionRepository).save(captor.capture());
		return captor.getValue();
	}

	private void assertBigDecimalEquals(BigDecimal expected, BigDecimal actual) {
		assertTrue(
				expected.compareTo(actual) == 0,
				() -> "Expected " + expected + " but was " + actual);
	}
}
