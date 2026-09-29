package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.OfferStatus;

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

/**
 * Marketplace Offer authorized by an immutable Food Eligibility Evaluation.
 *
 * <p>Offer lifecycle status has no food-safety meaning. The discount-range and
 * discounted-price checks must be enforced by the Offer service and a database
 * migration after the pending pricing decisions are approved.
 */
@Entity
@Check(
		name = "chk_offers_basic_invariants",
		constraints =
				"offered_quantity > 0"
						+ " and original_price > 0"
						+ " and offer_price >= 0"
						+ " and expires_at > start_at")
@Table(
		name = "offers",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_offers_public_id", columnNames = "public_id"),
				@UniqueConstraint(
						name = "uk_offers_eligibility_evaluation",
						columnNames = "eligibility_evaluation_id")
		},
		indexes = {
				@Index(
						name = "idx_offers_inventory_status_expires",
						columnList = "inventory_id, status, expires_at")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Offer {

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
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "product_id", nullable = false, updatable = false)
	private Product product;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "inventory_id", nullable = false, updatable = false)
	private Inventory inventory;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "eligibility_evaluation_id", nullable = false, updatable = false)
	private FoodEligibilityEvaluation eligibilityEvaluation;

	@Column(
			name = "original_price",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 2)
	private BigDecimal originalPrice;

	@Column(
			name = "discount_percentage",
			nullable = false,
			updatable = false,
			precision = 5,
			scale = 2)
	private BigDecimal discountPercentage;

	@Column(
			name = "offer_price",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 2)
	private BigDecimal offerPrice;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(
			name = "currency_code",
			nullable = false,
			updatable = false,
			length = 3,
			columnDefinition = "CHAR(3)")
	private String currencyCode;

	@Column(
			name = "offered_quantity",
			nullable = false,
			updatable = false,
			precision = 12,
			scale = 3)
	private BigDecimal offeredQuantity;

	@Column(
			name = "start_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant startAt;

	@Column(
			name = "expires_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	private Instant expiresAt;

	@Enumerated(EnumType.STRING)
	@Column(
			name = "status",
			nullable = false,
			length = 32,
			columnDefinition = "VARCHAR(32)")
	@Setter
	private OfferStatus status = OfferStatus.ACTIVE;

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

	public Offer(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			FoodEligibilityEvaluation eligibilityEvaluation,
			BigDecimal originalPrice,
			BigDecimal discountPercentage,
			BigDecimal offerPrice,
			String currencyCode,
			BigDecimal offeredQuantity,
			Instant startAt,
			Instant expiresAt) {
		this.restaurant = restaurant;
		this.product = product;
		this.inventory = inventory;
		this.eligibilityEvaluation = eligibilityEvaluation;
		this.originalPrice = originalPrice;
		this.discountPercentage = discountPercentage;
		this.offerPrice = offerPrice;
		this.currencyCode = currencyCode;
		this.offeredQuantity = offeredQuantity;
		this.startAt = startAt;
		this.expiresAt = expiresAt;
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		if (status == null) {
			status = OfferStatus.ACTIVE;
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
