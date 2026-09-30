package com.foodsaver.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

	Optional<Customer> findByPublicId(UUID publicId);

	Optional<Customer> findByEmail(String email);

	boolean existsByEmail(String email);
}
