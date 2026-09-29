package com.foodsaver.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.foodsaver.entity.Offer;
import com.foodsaver.enums.OfferStatus;

import jakarta.persistence.LockModeType;

public interface OfferRepository extends JpaRepository<Offer, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Offer> findFirstByInventoryIdAndStatusAndExpiresAtAfterOrderByIdAsc(
			Long inventoryId,
			OfferStatus status,
			Instant transactionTime);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Offer> findFirstByEligibilityEvaluationId(Long eligibilityEvaluationId);

	boolean existsByEligibilityEvaluationId(Long eligibilityEvaluationId);
}
