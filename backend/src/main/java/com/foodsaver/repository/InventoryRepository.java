package com.foodsaver.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.foodsaver.entity.Inventory;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	Optional<Inventory> findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Inventory> findByIdAndRestaurantId(Long id, Long restaurantId);

	boolean existsByRestaurantIdAndProductIdAndInventoryDate(
			Long restaurantId,
			Long productId,
			LocalDate inventoryDate);
}
