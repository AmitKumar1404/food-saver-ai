package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByPublicId(UUID publicId);

	Optional<Reservation> findByPublicIdAndCustomerId(
			UUID publicId,
			Long customerId);

	Optional<Reservation> findByCustomerIdAndIdempotencyKey(
			Long customerId,
			String idempotencyKey);

	boolean existsByCustomerIdAndIdempotencyKey(
			Long customerId,
			String idempotencyKey);
}
