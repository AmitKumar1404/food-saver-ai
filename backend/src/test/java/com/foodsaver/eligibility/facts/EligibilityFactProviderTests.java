package com.foodsaver.eligibility.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.ProductCategory;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.enums.SurplusDetectionStatus;
import com.foodsaver.exception.InventoryNotFoundException;
import com.foodsaver.exception.ProductNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.exception.SurplusDetectionNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.SurplusDetectionRepository;

@ExtendWith(MockitoExtension.class)
class EligibilityFactProviderTests {

	private static final Long RESTAURANT_ID = 1L;
	private static final Long OTHER_RESTAURANT_ID = 2L;
	private static final UUID RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID OTHER_RESTAURANT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID PRODUCT_PUBLIC_ID = UUID.randomUUID();
	private static final UUID INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID OTHER_INVENTORY_PUBLIC_ID = UUID.randomUUID();
	private static final UUID SURPLUS_DETECTION_PUBLIC_ID = UUID.randomUUID();

	@Mock
	private RestaurantRepository restaurantRepository;

	@Mock
	private InventoryRepository inventoryRepository;

	@Mock
	private ProductRepository productRepository;

	@Mock
	private SurplusDetectionRepository surplusDetectionRepository;

	private EligibilityFactProvider provider;

	@BeforeEach
	void setUp() {
		provider = new EligibilityFactProvider(
				restaurantRepository,
				inventoryRepository,
				productRepository,
				surplusDetectionRepository);
	}

	@Test
	void loadsFactsForValidOwnershipChain() {
		Restaurant restaurant = restaurant(
				RESTAURANT_ID,
				RESTAURANT_PUBLIC_ID);
		Product product = product(PRODUCT_PUBLIC_ID, restaurant);
		Inventory inventory = inventory(INVENTORY_PUBLIC_ID, restaurant, product);
		SurplusDetection surplusDetection = surplusDetection(
				SURPLUS_DETECTION_PUBLIC_ID,
				inventory);
		arrangeValidOwnershipChain(restaurant, product, inventory, surplusDetection);
		Instant beforeEvaluation = Instant.now();

		FoodEligibilityFacts facts = provider.loadFacts(
				RESTAURANT_PUBLIC_ID,
				INVENTORY_PUBLIC_ID,
				SURPLUS_DETECTION_PUBLIC_ID);
		Instant afterEvaluation = Instant.now();

		assertEquals(RESTAURANT_PUBLIC_ID, facts.restaurant().publicId());
		assertEquals(BusinessType.RESTAURANT.name(), facts.restaurant().businessType());
		assertEquals(PRODUCT_PUBLIC_ID, facts.product().publicId());
		assertEquals(INVENTORY_PUBLIC_ID, facts.inventory().publicId());
		assertEquals(SURPLUS_DETECTION_PUBLIC_ID, facts.surplusDetection().publicId());
		assertEquals(inventory.getVersion(), facts.inventory().version());
		assertEquals(
				surplusDetection.getDetectedAt(),
				facts.surplusDetection().detectedAt());
		assertFalse(facts.evaluatedAt().isBefore(beforeEvaluation));
		assertFalse(facts.evaluatedAt().isAfter(afterEvaluation));
	}

	@Test
	void rejectsMissingRestaurant() {
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				RestaurantNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	@Test
	void rejectsMissingInventory() {
		Restaurant restaurant = restaurant(RESTAURANT_ID, RESTAURANT_PUBLIC_ID);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(restaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				InventoryNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	@Test
	void rejectsInventoryOwnedByAnotherRestaurant() {
		Restaurant requestedRestaurant = restaurant(
				RESTAURANT_ID,
				RESTAURANT_PUBLIC_ID);
		Restaurant otherRestaurant = restaurant(
				OTHER_RESTAURANT_ID,
				OTHER_RESTAURANT_PUBLIC_ID);
		Product otherProduct = product(PRODUCT_PUBLIC_ID, otherRestaurant);
		Inventory inventoryOwnedByOtherRestaurant = inventory(
				INVENTORY_PUBLIC_ID,
				otherRestaurant,
				otherProduct);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(requestedRestaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(inventoryOwnedByOtherRestaurant));

		assertThrows(
				InventoryNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	@Test
	void rejectsProductOwnedByAnotherRestaurant() {
		Restaurant requestedRestaurant = restaurant(
				RESTAURANT_ID,
				RESTAURANT_PUBLIC_ID);
		Restaurant otherRestaurant = restaurant(
				OTHER_RESTAURANT_ID,
				OTHER_RESTAURANT_PUBLIC_ID);
		Product productOwnedByOtherRestaurant = product(
				PRODUCT_PUBLIC_ID,
				otherRestaurant);
		Inventory inventory = inventory(
				INVENTORY_PUBLIC_ID,
				requestedRestaurant,
				productOwnedByOtherRestaurant);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(requestedRestaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(inventory));
		when(productRepository.findByPublicIdAndRestaurantId(
				PRODUCT_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(productOwnedByOtherRestaurant));

		assertThrows(
				ProductNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	@Test
	void rejectsMissingSurplusDetection() {
		Restaurant restaurant = restaurant(RESTAURANT_ID, RESTAURANT_PUBLIC_ID);
		Product product = product(PRODUCT_PUBLIC_ID, restaurant);
		Inventory inventory = inventory(INVENTORY_PUBLIC_ID, restaurant, product);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(restaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(inventory));
		when(productRepository.findByPublicIdAndRestaurantId(
				PRODUCT_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(product));
		when(surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						SURPLUS_DETECTION_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.empty());

		assertThrows(
				SurplusDetectionNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	@Test
	void rejectsSurplusDetectionBelongingToAnotherInventory() {
		Restaurant restaurant = restaurant(RESTAURANT_ID, RESTAURANT_PUBLIC_ID);
		Product product = product(PRODUCT_PUBLIC_ID, restaurant);
		Inventory requestedInventory = inventory(
				INVENTORY_PUBLIC_ID,
				restaurant,
				product);
		Inventory otherInventory = inventory(
				OTHER_INVENTORY_PUBLIC_ID,
				restaurant,
				product);
		SurplusDetection detectionForOtherInventory = surplusDetection(
				SURPLUS_DETECTION_PUBLIC_ID,
				otherInventory);
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(restaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(requestedInventory));
		when(productRepository.findByPublicIdAndRestaurantId(
				PRODUCT_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(product));
		when(surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						SURPLUS_DETECTION_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(detectionForOtherInventory));

		assertThrows(
				SurplusDetectionNotFoundException.class,
				() -> provider.loadFacts(
						RESTAURANT_PUBLIC_ID,
						INVENTORY_PUBLIC_ID,
						SURPLUS_DETECTION_PUBLIC_ID));
	}

	private void arrangeValidOwnershipChain(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			SurplusDetection surplusDetection) {
		when(restaurantRepository.findByPublicId(RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(restaurant));
		when(inventoryRepository.findByPublicIdAndRestaurantId(
				INVENTORY_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(inventory));
		when(productRepository.findByPublicIdAndRestaurantId(
				PRODUCT_PUBLIC_ID,
				RESTAURANT_ID))
				.thenReturn(Optional.of(product));
		when(surplusDetectionRepository
				.findByPublicIdAndInventoryRestaurantPublicId(
						SURPLUS_DETECTION_PUBLIC_ID,
						RESTAURANT_PUBLIC_ID))
				.thenReturn(Optional.of(surplusDetection));
	}

	private Restaurant restaurant(Long id, UUID publicId) {
		Restaurant restaurant = new Restaurant();
		ReflectionTestUtils.setField(restaurant, "id", id);
		ReflectionTestUtils.setField(restaurant, "publicId", publicId);
		restaurant.setBusinessType(BusinessType.RESTAURANT);
		restaurant.setCountryCode("IN");
		restaurant.setStatus(RestaurantStatus.ACTIVE);
		restaurant.setTimezone("Asia/Kolkata");
		return restaurant;
	}

	private Product product(UUID publicId, Restaurant restaurant) {
		Product product = new Product();
		ReflectionTestUtils.setField(product, "publicId", publicId);
		product.setRestaurant(restaurant);
		product.setCategory(ProductCategory.MAIN_COURSE);
		product.setStatus(ProductStatus.ACTIVE);
		return product;
	}

	private Inventory inventory(
			UUID publicId,
			Restaurant restaurant,
			Product product) {
		Inventory inventory = new Inventory();
		ReflectionTestUtils.setField(inventory, "publicId", publicId);
		ReflectionTestUtils.setField(inventory, "version", 3L);
		inventory.setRestaurant(restaurant);
		inventory.setProduct(product);
		inventory.setInventoryDate(LocalDate.of(2026, 9, 29));
		inventory.setStatus(InventoryStatus.ACTIVE);
		inventory.setAvailableQuantity(new BigDecimal("8.000"));
		return inventory;
	}

	private SurplusDetection surplusDetection(UUID publicId, Inventory inventory) {
		SurplusDetection surplusDetection = new SurplusDetection();
		ReflectionTestUtils.setField(surplusDetection, "publicId", publicId);
		ReflectionTestUtils.setField(
				surplusDetection,
				"detectedAt",
				Instant.parse("2026-09-29T06:00:00Z"));
		surplusDetection.setInventory(inventory);
		surplusDetection.setStatus(SurplusDetectionStatus.POTENTIAL_SURPLUS);
		surplusDetection.setDetectedQuantity(new BigDecimal("8.000"));
		surplusDetection.setThresholdQuantity(new BigDecimal("5.000"));
		return surplusDetection;
	}
}
