package com.foodsaver.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.OrderingReconciliationMarker;

import jakarta.persistence.LockModeType;

public interface OrderingReconciliationMarkerRepository
		extends JpaRepository<OrderingReconciliationMarker, String> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select marker
			from OrderingReconciliationMarker marker
			where marker.releaseIdentifier = :releaseIdentifier
			""")
	Optional<OrderingReconciliationMarker> findByReleaseIdentifierForActivation(
			@Param("releaseIdentifier") String releaseIdentifier);
}
