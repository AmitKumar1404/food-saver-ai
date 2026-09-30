package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.Product;

import jakarta.persistence.LockModeType;

public interface ProductRepository extends JpaRepository<Product, Long> {

	Optional<Product> findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select product
			from Product product
			where product.id = :productId
			  and product.restaurant.id = :restaurantId
			""")
	Optional<Product> findByIdAndRestaurantIdForAllocation(
			@Param("productId") Long productId,
			@Param("restaurantId") Long restaurantId);
}
