package com.foodsaver.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.foodsaver.entity.Inventory;

import jakarta.persistence.LockModeType;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

	Optional<Inventory> findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Inventory> findByIdAndRestaurantId(Long id, Long restaurantId);

	@Query("""
			select inventory
			from Inventory inventory
			order by inventory.id
			""")
	List<Inventory> findAllByOrderByIdForReconciliation();

	boolean existsByRestaurantIdAndProductIdAndInventoryDate(
			Long restaurantId,
			Long productId,
			LocalDate inventoryDate);
}
