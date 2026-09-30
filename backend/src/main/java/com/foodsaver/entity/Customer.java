package com.foodsaver.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.CustomerStatus;

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
		name = "customers",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_customers_public_id", columnNames = "public_id"),
				@UniqueConstraint(name = "uk_customers_email", columnNames = "email")
		},
		indexes = {
				@Index(
						name = "idx_customers_status_created",
						columnList = "status, created_at")
		})
@Getter
@Setter
@NoArgsConstructor
public class Customer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Long id;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(
			name = "public_id",
			nullable = false,
			updatable = false,
			length = 36,
			columnDefinition = "CHAR(36)")
	@Setter(AccessLevel.NONE)
	private UUID publicId;

	@Column(name = "email", nullable = false, length = 254)
	private String email;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Column(name = "contact_phone", length = 32)
	private String contactPhone;

	@Enumerated(EnumType.STRING)
	@Column(
			name = "status",
			nullable = false,
			length = 32,
			columnDefinition = "VARCHAR(32)")
	private CustomerStatus status = CustomerStatus.ACTIVE;

	@Version
	@Column(name = "version", nullable = false)
	@Setter(AccessLevel.NONE)
	private Long version;

	@Column(
			name = "created_at",
			nullable = false,
			updatable = false,
			columnDefinition = "TIMESTAMP(6)")
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@Column(
			name = "updated_at",
			nullable = false,
			columnDefinition = "TIMESTAMP(6)")
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	public void setEmail(String email) {
		this.email = canonicalizeEmail(email);
	}

	/**
	 * Returns the canonical representation used for persistence and duplicate
	 * checks. V1 intentionally performs lowercase conversion only.
	 */
	public static String canonicalizeEmail(String email) {
		return email == null ? null : email.toLowerCase(Locale.ROOT);
	}

	@PrePersist
	void initializeSystemFields() {
		email = canonicalizeEmail(email);
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		if (status == null) {
			status = CustomerStatus.ACTIVE;
		}

		Instant now = Instant.now();
		if (createdAt == null) {
			createdAt = now;
		}
		updatedAt = now;
	}

	@PreUpdate
	void updateSystemFields() {
		email = canonicalizeEmail(email);
		updatedAt = Instant.now();
	}
}
