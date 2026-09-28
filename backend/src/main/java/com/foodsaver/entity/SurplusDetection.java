package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.SurplusDetectionStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
		name = "surplus_detections",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_surplus_detections_public_id",
						columnNames = "public_id")
		},
		indexes = {
				@Index(
						name = "idx_surplus_detections_inventory_detected_at",
						columnList = "inventory_id, detected_at"),
				@Index(
						name = "idx_surplus_detections_status_detected_at",
						columnList = "status, detected_at")
		})
@Getter
@Setter
@NoArgsConstructor
public class SurplusDetection {

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
	@JoinColumn(name = "inventory_id", nullable = false)
	private Inventory inventory;

	@Column(name = "detected_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal detectedQuantity;

	@Column(name = "threshold_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal thresholdQuantity;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private SurplusDetectionStatus status;

	@Column(name = "detected_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant detectedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}

		Instant now = Instant.now();
		if (detectedAt == null) {
			detectedAt = now;
		}
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
