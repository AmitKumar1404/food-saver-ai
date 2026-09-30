package com.foodsaver.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.Offer;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.repository.projection.OfferAllocationTarget;

import jakarta.persistence.LockModeType;

public interface OfferRepository extends JpaRepository<Offer, Long> {

	Optional<Offer> findByPublicId(UUID publicId);

	@Query("""
			select new com.foodsaver.repository.projection.OfferAllocationTarget(
				offer.id,
				offer.restaurant.id,
				offer.product.id,
				offer.inventory.id)
			from Offer offer
			where offer.publicId = :offerPublicId
			""")
	Optional<OfferAllocationTarget> findAllocationTargetByPublicId(
			@Param("offerPublicId") UUID offerPublicId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select offer
			from Offer offer
			where offer.id in :offerIds
			order by offer.id
			""")
	List<Offer> findAllByIdInOrderByIdForAllocation(
			@Param("offerIds") Collection<Long> offerIds);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Offer> findFirstByInventoryIdAndStatusAndExpiresAtAfterOrderByIdAsc(
			Long inventoryId,
			OfferStatus status,
			Instant transactionTime);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Offer> findFirstByEligibilityEvaluationId(Long eligibilityEvaluationId);

	boolean existsByEligibilityEvaluationId(Long eligibilityEvaluationId);
}
