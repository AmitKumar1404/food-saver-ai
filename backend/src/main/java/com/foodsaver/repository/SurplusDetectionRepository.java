package com.foodsaver.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.SurplusDetection;

public interface SurplusDetectionRepository extends JpaRepository<SurplusDetection, Long> {

	Optional<SurplusDetection> findByPublicIdAndInventoryRestaurantPublicId(
			UUID publicId,
			UUID restaurantPublicId);

	List<SurplusDetection> findByInventoryPublicIdOrderByDetectedAtDesc(UUID inventoryPublicId);
}
