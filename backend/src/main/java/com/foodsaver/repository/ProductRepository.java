package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {

	Optional<Product> findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId);
}
