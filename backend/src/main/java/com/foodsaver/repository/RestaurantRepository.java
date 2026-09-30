package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.Restaurant;

import jakarta.persistence.LockModeType;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

	Optional<Restaurant> findByPublicId(UUID publicId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select restaurant
			from Restaurant restaurant
			where restaurant.id = :restaurantId
			""")
	Optional<Restaurant> findByIdForAllocation(
			@Param("restaurantId") Long restaurantId);
}
