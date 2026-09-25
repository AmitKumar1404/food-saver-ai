package com.foodsaver.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.RestaurantStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
		name = "restaurants",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_restaurants_public_id", columnNames = "public_id")
		},
		indexes = {
				@Index(name = "idx_restaurants_status_id", columnList = "status, id"),
				@Index(
						name = "idx_restaurants_country_city_status",
						columnList = "country_code, city, status")
		})
@Getter
@Setter
@NoArgsConstructor
public class Restaurant {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Long id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "public_id", nullable = false, updatable = false, length = 36)
	@Setter(AccessLevel.NONE)
	private UUID publicId;

	@Column(name = "name", nullable = false, length = 150)
	private String name;

	@Column(name = "legal_name", length = 200)
	private String legalName;

	@Enumerated(EnumType.STRING)
	@Column(name = "business_type", nullable = false, length = 32)
	private BusinessType businessType;

	@Column(name = "description", length = 1000)
	private String description;

	@Column(name = "contact_email", nullable = false, length = 254)
	private String contactEmail;

	@Column(name = "contact_phone", nullable = false, length = 32)
	private String contactPhone;

	@Column(name = "website_url", length = 2048)
	private String websiteUrl;

	@Column(name = "address_line_1", nullable = false, length = 255)
	private String addressLine1;

	@Column(name = "address_line_2", length = 255)
	private String addressLine2;

	@Column(name = "city", nullable = false, length = 100)
	private String city;

	@Column(name = "state_province", nullable = false, length = 100)
	private String stateProvince;

	@Column(name = "postal_code", nullable = false, length = 20)
	private String postalCode;

	@Column(name = "country_code", nullable = false, length = 2)
	private String countryCode;

	@Column(name = "latitude", precision = 9, scale = 6)
	private BigDecimal latitude;

	@Column(name = "longitude", precision = 9, scale = 6)
	private BigDecimal longitude;

	@Column(name = "timezone", nullable = false, length = 50)
	private String timezone;

	@Column(name = "currency_code", nullable = false, length = 3)
	private String currencyCode;

	@Column(name = "pickup_instructions", length = 500)
	private String pickupInstructions;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private RestaurantStatus status = RestaurantStatus.PENDING_VERIFICATION;

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
			status = RestaurantStatus.PENDING_VERIFICATION;
		}

		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
