package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Check(
		name = "chk_order_items_basic_invariants",
		constraints =
				"quantity > 0"
						+ " and unit_price > 0"
						+ " and total_amount > 0")
@Table(
		name = "order_items",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_order_items_public_id",
						columnNames = "public_id"),
				@UniqueConstraint(
						name = "uk_order_items_reservation",
						columnNames = "reservation_id"),
				@UniqueConstraint(
						name = "uk_order_items_order",
						columnNames = "order_id")
		},
		indexes = {
				@Index(name = "idx_order_items_offer", columnList = "offer_id"),
				@Index(name = "idx_order_items_inventory", columnList = "inventory_id")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

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
	@JoinColumn(name = "order_id", nullable = false, updatable = false)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reservation_id", nullable = false, updatable = false)
	private Reservation reservation;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "offer_id", nullable = false, updatable = false)
	private Offer offer;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "inventory_id", nullable = false, updatable = false)
	private Inventory inventory;

	@Column(
			name = "product_name_snapshot",
			nullable = false,
			updatable = false,
			length = 150)
	private String productNameSnapshot;

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

	@Column(
			name = "created_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant createdAt;

	public OrderItem(
			Order order,
			Reservation reservation,
			Offer offer,
			Product product,
			Inventory inventory,
			String productNameSnapshot,
			BigDecimal quantity,
			BigDecimal unitPrice,
			BigDecimal totalAmount,
			String currencyCode,
			Instant transactionTime) {
		this.order = order;
		this.reservation = reservation;
		this.offer = offer;
		this.product = product;
		this.inventory = inventory;
		this.productNameSnapshot = productNameSnapshot;
		this.quantity = quantity;
		this.unitPrice = unitPrice;
		this.totalAmount = totalAmount;
		this.currencyCode = currencyCode;
		this.createdAt = Reservation.normalizeTimestamp(transactionTime);
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		createdAt = createdAt == null
				? Reservation.normalizeTimestamp(Instant.now())
				: Reservation.normalizeTimestamp(createdAt);
	}
}
