package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.Customer;

import jakarta.persistence.LockModeType;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

	Optional<Customer> findByPublicId(UUID publicId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select customer
			from Customer customer
			where customer.publicId = :publicId
			""")
	Optional<Customer> findByPublicIdForAllocation(
			@Param("publicId") UUID publicId);

	Optional<Customer> findByEmail(String email);

	boolean existsByEmail(String email);
}
