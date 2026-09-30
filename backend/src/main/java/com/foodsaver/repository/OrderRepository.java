package com.foodsaver.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

	Optional<Order> findByCustomerIdAndIdempotencyKey(
			Long customerId,
			String idempotencyKey);

	Optional<Order> findByCustomerPublicIdAndIdempotencyKey(
			UUID customerPublicId,
			String idempotencyKey);

	Optional<Order> findByPublicIdAndCustomerPublicId(
			UUID publicId,
			UUID customerPublicId);

	List<Order> findAllByCustomerId(Long customerId);
}
