package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Restaurant;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

	Optional<Restaurant> findByPublicId(UUID publicId);
}
