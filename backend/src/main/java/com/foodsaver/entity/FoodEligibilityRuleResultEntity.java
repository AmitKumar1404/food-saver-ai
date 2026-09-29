package com.foodsaver.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.foodsaver.enums.FoodEligibilityReasonCode;
import com.foodsaver.enums.FoodEligibilityRuleOutcome;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
		name = "food_eligibility_rule_results",
		uniqueConstraints = {
				@UniqueConstraint(
						name = "uk_food_eligibility_rule_results_public_id",
						columnNames = "public_id")
		},
		indexes = {
				@Index(
						name = "idx_food_eligibility_rule_results_evaluation",
						columnList = "evaluation_id")
		})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FoodEligibilityRuleResultEntity {

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
	@JoinColumn(name = "evaluation_id", nullable = false, updatable = false)
	private FoodEligibilityEvaluation evaluation;

	@Column(name = "rule_code", nullable = false, updatable = false, length = 100)
	private String ruleCode;

	@Enumerated(EnumType.STRING)
	@Column(name = "outcome", nullable = false, updatable = false, length = 16)
	private FoodEligibilityRuleOutcome outcome;

	@Enumerated(EnumType.STRING)
	@Column(name = "reason_code", updatable = false, length = 64)
	private FoodEligibilityReasonCode reasonCode;

	@Column(name = "message", nullable = false, updatable = false, length = 500)
	private String message;

	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	public FoodEligibilityRuleResultEntity(
			FoodEligibilityEvaluation evaluation,
			String ruleCode,
			FoodEligibilityRuleOutcome outcome,
			FoodEligibilityReasonCode reasonCode,
			String message) {
		this.evaluation = evaluation;
		this.ruleCode = ruleCode;
		this.outcome = outcome;
		this.reasonCode = reasonCode;
		this.message = message;
	}

	@PrePersist
	void initializeSystemFields() {
		if (publicId == null) {
			publicId = UUID.randomUUID();
		}
		if (createdAt == null) {
			createdAt = Instant.now();
		}
	}
}
