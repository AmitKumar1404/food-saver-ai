package com.foodsaver.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerCreateRequest {

	@NotBlank(message = "Customer email is required")
	@Email(message = "Customer email must be a valid email address")
	@Size(max = 254, message = "Customer email must not exceed 254 characters")
	private String email;

	@NotBlank(message = "Display name is required")
	@Size(max = 100, message = "Display name must not exceed 100 characters")
	private String displayName;

	@Size(max = 32, message = "Contact phone must not exceed 32 characters")
	private String contactPhone;
}
