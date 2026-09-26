package com.foodsaver.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.BusinessType;
import com.foodsaver.enums.RestaurantStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RestaurantResponse {

	private UUID publicId;

	private String name;

	private String legalName;

	private BusinessType businessType;

	private String description;

	private String contactEmail;

	private String contactPhone;

	private String websiteUrl;

	private String addressLine1;

	private String addressLine2;

	private String city;

	private String stateProvince;

	private String postalCode;

	private String countryCode;

	private BigDecimal latitude;

	private BigDecimal longitude;

	private String timezone;

	private String currencyCode;

	private String pickupInstructions;

	private RestaurantStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
