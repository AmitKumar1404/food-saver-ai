package com.foodsaver.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	Optional<Inventory> findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId);

	boolean existsByRestaurantIdAndProductIdAndInventoryDate(
			Long restaurantId,
			Long productId,
			LocalDate inventoryDate);
}
