package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.OrderStatus;

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

@Entity
@Check(
		name = "chk_customer_orders_basic_invariants",
		constraints =
				"total_amount > 0"
						+ " and ((status = 'CONFIRMED' and completed_at is null)"
						+ " or (status = 'COMPLETED' and completed_at is not null))")
@Table(
		name = "customer_orders",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_customer_orders_public_id",
						columnNames = "public_id"),
				@UniqueConstraint(
						name = "uk_customer_orders_customer_idempotency",
						columnNames = {"customer_id", "idempotency_key"})
		},
		indexes = {
				@Index(
						name = "idx_customer_orders_customer_status_created",
						columnList = "customer_id, status, created_at"),
				@Index(
						name = "idx_customer_orders_restaurant_status_created",
						columnList = "restaurant_id, status, created_at")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

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

	@Enumerated(EnumType.STRING)
	@Column(
			name = "status",
			nullable = false,
			length = 32,
			columnDefinition = "VARCHAR(32)")
	private OrderStatus status = OrderStatus.CONFIRMED;

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

	@Column(
			name = "confirmed_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant confirmedAt;

	@Column(name = "completed_at", columnDefinition = "TIMESTAMP(6)")
	private Instant completedAt;

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

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	public Order(
			Customer customer,
			Restaurant restaurant,
			BigDecimal totalAmount,
			String currencyCode,
			String idempotencyKey,
			String requestHash,
			Instant transactionTime) {
		this.customer = customer;
		this.restaurant = restaurant;
		this.totalAmount = totalAmount;
		this.currencyCode = currencyCode;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		Instant normalized = Reservation.normalizeTimestamp(transactionTime);
		this.confirmedAt = normalized;
		this.createdAt = normalized;
		this.updatedAt = normalized;
	}

	public void complete(Instant transactionTime) {
		if (status != OrderStatus.CONFIRMED || completedAt != null) {
			throw new IllegalStateException("Order cannot be completed");
		}
		Instant normalized = Reservation.normalizeTimestamp(transactionTime);
		if (normalized == null) {
			throw new IllegalArgumentException(
					"Order completion timestamp is required");
		}
		status = OrderStatus.COMPLETED;
		completedAt = normalized;
		updatedAt = normalized;
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		if (status == null) {
			status = OrderStatus.CONFIRMED;
		}
		Instant now = Reservation.normalizeTimestamp(Instant.now());
		confirmedAt = confirmedAt == null
				? now
				: Reservation.normalizeTimestamp(confirmedAt);
		createdAt = createdAt == null
				? confirmedAt
				: Reservation.normalizeTimestamp(createdAt);
		updatedAt = updatedAt == null
				? createdAt
				: Reservation.normalizeTimestamp(updatedAt);
		completedAt = Reservation.normalizeTimestamp(completedAt);
	}

	@PreUpdate
	void normalizeTimestamps() {
		confirmedAt = Reservation.normalizeTimestamp(confirmedAt);
		completedAt = Reservation.normalizeTimestamp(completedAt);
		createdAt = Reservation.normalizeTimestamp(createdAt);
		updatedAt = Reservation.normalizeTimestamp(updatedAt);
	}
}
