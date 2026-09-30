package com.foodsaver.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AiChatRequest {

	@NotBlank(message = "Message is required")
	private String message;
}
