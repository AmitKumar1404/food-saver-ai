package com.foodsaver.dto.request;

import java.math.BigDecimal;
import java.time.ZoneId;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.foodsaver.enums.BusinessType;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RestaurantRequest {

	@NotBlank(message = "Restaurant name is required")
	@Size(max = 150, message = "Restaurant name must not exceed 150 characters")
	private String name;

	@Size(max = 200, message = "Legal name must not exceed 200 characters")
	private String legalName;

	@NotNull(message = "Business type is required")
	private BusinessType businessType;

	@Size(max = 1000, message = "Description must not exceed 1000 characters")
	private String description;

	@NotBlank(message = "Contact email is required")
	@Email(message = "Contact email must be a valid email address")
	@Size(max = 254, message = "Contact email must not exceed 254 characters")
	private String contactEmail;

	@NotBlank(message = "Contact phone is required")
	@Size(max = 32, message = "Contact phone must not exceed 32 characters")
	@Pattern(
			regexp = "^\\+[1-9]\\d{1,14}$",
			message = "Contact phone must use E.164 format, for example +14155552671")
	private String contactPhone;

	@Size(max = 2048, message = "Website URL must not exceed 2048 characters")
	@Pattern(
			regexp = "(?i)^https?://\\S+$",
			message = "Website URL must start with http:// or https://")
	private String websiteUrl;

	@NotBlank(message = "Address line 1 is required")
	@Size(max = 255, message = "Address line 1 must not exceed 255 characters")
	private String addressLine1;

	@Size(max = 255, message = "Address line 2 must not exceed 255 characters")
	private String addressLine2;

	@NotBlank(message = "City is required")
	@Size(max = 100, message = "City must not exceed 100 characters")
	private String city;

	@NotBlank(message = "State or province is required")
	@Size(max = 100, message = "State or province must not exceed 100 characters")
	private String stateProvince;

	@NotBlank(message = "Postal code is required")
	@Size(max = 20, message = "Postal code must not exceed 20 characters")
	private String postalCode;

	@NotBlank(message = "Country code is required")
	@Pattern(
			regexp = "^[A-Z]{2}$",
			message = "Country code must be a 2-letter uppercase ISO-style code")
	private String countryCode;

	@DecimalMin(value = "-90.0", message = "Latitude must be greater than or equal to -90")
	@DecimalMax(value = "90.0", message = "Latitude must be less than or equal to 90")
	private BigDecimal latitude;

	@DecimalMin(value = "-180.0", message = "Longitude must be greater than or equal to -180")
	@DecimalMax(value = "180.0", message = "Longitude must be less than or equal to 180")
	private BigDecimal longitude;

	@NotBlank(message = "Timezone is required")
	@Size(max = 50, message = "Timezone must not exceed 50 characters")
	private String timezone;

	@NotBlank(message = "Currency code is required")
	@Pattern(
			regexp = "^[A-Z]{3}$",
			message = "Currency code must be a 3-letter uppercase ISO-style code")
	private String currencyCode;

	@Size(max = 500, message = "Pickup instructions must not exceed 500 characters")
	private String pickupInstructions;

	@JsonIgnore
	@AssertTrue(message = "Timezone must be a valid IANA time-zone ID")
	public boolean isTimezoneValid() {
		return timezone == null
				|| timezone.isBlank()
				|| ZoneId.getAvailableZoneIds().contains(timezone);
	}
}
