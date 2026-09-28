package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.SurplusDetectionStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SurplusDetectionResponse {

	private UUID publicId;

	private UUID inventoryPublicId;

	private BigDecimal detectedQuantity;

	private BigDecimal thresholdQuantity;

	private SurplusDetectionStatus status;

	private Instant detectedAt;

	private Instant createdAt;

	private Instant updatedAt;
}
