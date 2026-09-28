package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.foodsaver.enums.InventoryStatus;
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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
		name = "inventory",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_inventory_restaurant_product_date",
						columnNames = {"restaurant_id", "product_id", "inventory_date"})
		},
		indexes = {
				@Index(
						name = "idx_inventory_restaurant_date",
						columnList = "restaurant_id, inventory_date"),
				@Index(
						name = "idx_inventory_restaurant_product",
						columnList = "restaurant_id, product_id"),
				@Index(
						name = "idx_inventory_restaurant_status",
						columnList = "restaurant_id, status")
		})
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

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
	@JoinColumn(name = "restaurant_id", nullable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false)
	private Product product;

	@Column(name = "prepared_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal preparedQuantity;

	@Column(name = "available_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal availableQuantity;

	@Column(name = "reserved_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal reservedQuantity;

	@Column(name = "sold_quantity", nullable = false, precision = 12, scale = 3)
	private BigDecimal soldQuantity;

	@Column(name = "inventory_date", nullable = false)
	private LocalDate inventoryDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private InventoryStatus status = InventoryStatus.ACTIVE;

	@Version
	@Column(name = "version", nullable = false)
	@Setter(AccessLevel.NONE)
	private Long version;

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
		if (status == null) {
			status = InventoryStatus.ACTIVE;
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
