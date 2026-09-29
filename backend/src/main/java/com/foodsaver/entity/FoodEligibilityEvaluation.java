package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.FoodEligibilityStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
		name = "food_eligibility_evaluations",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_food_eligibility_evaluations_public_id",
						columnNames = "public_id")
		},
		indexes = {
				@Index(
						name = "idx_food_eligibility_evaluations_detection_time",
						columnList = "surplus_detection_id, evaluated_at"),
				@Index(
						name = "idx_food_eligibility_evaluations_status_time",
						columnList = "status, evaluated_at")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodEligibilityEvaluation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Long id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "public_id", nullable = false, updatable = false, length = 36)
	@Setter(AccessLevel.NONE)
	private UUID publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "surplus_detection_id", nullable = false, updatable = false)
	private SurplusDetection surplusDetection;

	@Column(name = "policy_key", nullable = false, updatable = false, length = 100)
	private String policyKey;

	@Column(name = "policy_version", nullable = false, updatable = false, length = 50)
	private String policyVersion;

	@Column(
			name = "policy_source_reference",
			nullable = false,
			updatable = false,
			length = 255)
	private String policySourceReference;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, updatable = false, length = 32)
	private FoodEligibilityStatus status;

	@Column(
			name = "evaluated_inventory_version",
			nullable = false,
			updatable = false)
	private Long evaluatedInventoryVersion;

	@Column(
			name = "evaluated_available_quantity",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 3)
	private BigDecimal evaluatedAvailableQuantity;

	@Column(name = "evaluated_at", nullable = false, updatable = false)
	private Instant evaluatedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	public FoodEligibilityEvaluation(
			SurplusDetection surplusDetection,
			String policyKey,
			String policyVersion,
			String policySourceReference,
			FoodEligibilityStatus status,
			Long evaluatedInventoryVersion,
			BigDecimal evaluatedAvailableQuantity,
			Instant evaluatedAt) {
		this.surplusDetection = surplusDetection;
		this.policyKey = policyKey;
		this.policyVersion = policyVersion;
		this.policySourceReference = policySourceReference;
		this.status = status;
		this.evaluatedInventoryVersion = evaluatedInventoryVersion;
		this.evaluatedAvailableQuantity = evaluatedAvailableQuantity;
		this.evaluatedAt = evaluatedAt;
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}

		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
