package com.foodsaver.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.foodsaver.enums.CustomerStatus;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerResponse {

	private UUID publicId;

	private String email;

	private String displayName;

	private String contactPhone;

	private CustomerStatus status;

	private Instant createdAt;

	private Instant updatedAt;
}
