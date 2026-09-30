package com.foodsaver.entity;

import java.time.Instant;

import com.foodsaver.enums.OrderingReconciliationState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ordering_reconciliation_markers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderingReconciliationMarker {

	@Id
	@Column(name = "release_identifier", nullable = false, updatable = false, length = 64)
	private String releaseIdentifier;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", nullable = false, length = 32)
	private OrderingReconciliationState state;

	@Column(
			name = "reconciled_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant reconciledAt;

	@Column(name = "activated_at", columnDefinition = "TIMESTAMP(6)")
	private Instant activatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	public OrderingReconciliationMarker(
			String releaseIdentifier,
			Instant reconciledAt) {
		this.releaseIdentifier = releaseIdentifier;
		this.state = OrderingReconciliationState.RECONCILED_BASELINE;
		this.reconciledAt = reconciledAt;
	}

	public void activate(Instant activationTime) {
		if (state != OrderingReconciliationState.RECONCILED_BASELINE) {
			throw new IllegalStateException(
					"Ordering reconciliation marker cannot be activated");
		}
		state = OrderingReconciliationState.ACTIVATED;
		activatedAt = activationTime;
	}
}
