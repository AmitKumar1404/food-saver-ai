package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.ReservationStatus;

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
@Check(
		name = "chk_reservations_basic_invariants",
		constraints =
				"quantity > 0"
						+ " and unit_price > 0"
						+ " and total_amount > 0"
						+ " and expires_at > created_at")
@Table(
		name = "reservations",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_reservations_public_id",
						columnNames = "public_id"),
				@UniqueConstraint(
						name = "uk_reservations_customer_idempotency",
						columnNames = {"customer_id", "idempotency_key"})
		},
		indexes = {
				@Index(
						name = "idx_reservations_customer_status_created",
						columnList = "customer_id, status, created_at"),
				@Index(
						name = "idx_reservations_restaurant_status_created",
						columnList = "restaurant_id, status, created_at"),
				@Index(
						name = "idx_reservations_offer_status_expires",
						columnList = "offer_id, status, expires_at"),
				@Index(
						name = "idx_reservations_inventory_status",
						columnList = "inventory_id, status"),
				@Index(
						name = "idx_reservations_status_expires",
						columnList = "status, expires_at")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	private Long id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(
			name = "public_id",
			nullable = false,
			updatable = false,
			length = 36,
			columnDefinition = "CHAR(36)")
	private UUID publicId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "customer_id", nullable = false, updatable = false)
	private Customer customer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "offer_id", nullable = false, updatable = false)
	private Offer offer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "inventory_id", nullable = false, updatable = false)
	private Inventory inventory;

	@Column(
			name = "quantity",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 3)
	private BigDecimal quantity;

	@Column(
			name = "unit_price",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 2)
	private BigDecimal unitPrice;

	@Column(
			name = "total_amount",
			nullable = false,
			updatable = false,
			precision = 18,
			scale = 2)
	private BigDecimal totalAmount;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(
			name = "currency_code",
			nullable = false,
			updatable = false,
			length = 3,
			columnDefinition = "CHAR(3)")
	private String currencyCode;

	@Enumerated(EnumType.STRING)
	@Column(
			name = "status",
			nullable = false,
			length = 32,
			columnDefinition = "VARCHAR(32)")
	@Setter
	private ReservationStatus status = ReservationStatus.ACTIVE;

	@Column(
			name = "expires_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant expiresAt;

	@Column(
			name = "idempotency_key",
			nullable = false,
			updatable = false,
			length = 100)
	private String idempotencyKey;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(
			name = "request_hash",
			nullable = false,
			updatable = false,
			length = 64,
			columnDefinition = "CHAR(64)")
	private String requestHash;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(
			name = "created_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant createdAt;

	@Column(
			name = "updated_at",
			nullable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant updatedAt;

	@Column(name = "cancelled_at", columnDefinition = "TIMESTAMP(6)")
	@Setter
	private Instant cancelledAt;

	@Column(name = "expired_at", columnDefinition = "TIMESTAMP(6)")
	@Setter
	private Instant expiredAt;

	@Column(name = "converted_at", columnDefinition = "TIMESTAMP(6)")
	@Setter
	private Instant convertedAt;

	public Reservation(
			Customer customer,
			Restaurant restaurant,
			Offer offer,
			Inventory inventory,
			BigDecimal quantity,
			BigDecimal unitPrice,
			BigDecimal totalAmount,
			String currencyCode,
			Instant expiresAt,
			String idempotencyKey,
			String requestHash) {
		this.customer = customer;
		this.restaurant = restaurant;
		this.offer = offer;
		this.inventory = inventory;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		this.totalAmount = totalAmount;
		this.currencyCode = currencyCode;
		this.expiresAt = expiresAt;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		if (status == null) {
			status = ReservationStatus.ACTIVE;
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
