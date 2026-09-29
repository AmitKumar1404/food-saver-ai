package com.foodsaver.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.Offer;
import com.foodsaver.enums.OfferStatus;

public interface OfferRepository extends JpaRepository<Offer, Long> {

	boolean existsByInventoryIdAndStatusAndExpiresAtAfter(
			Long inventoryId,
			OfferStatus status,
			Instant transactionTime);

	boolean existsByEligibilityEvaluationId(Long eligibilityEvaluationId);
}
