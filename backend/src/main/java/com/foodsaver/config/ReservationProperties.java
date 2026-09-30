package com.foodsaver.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "foodsaver.ordering")
@Validated
@Getter
@Setter
public class ReservationProperties {

	@NotNull(message = "Reservation TTL is required")
	private Duration reservationTtl;

	private boolean allocationEnabled;
}
