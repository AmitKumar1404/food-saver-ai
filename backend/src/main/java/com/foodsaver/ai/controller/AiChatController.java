package com.foodsaver.ai.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.ai.dto.request.AiChatRequest;
import com.foodsaver.ai.dto.response.AiChatResponse;
import com.foodsaver.ai.service.AiChatService;
import com.foodsaver.exception.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@Profile("local")
@RequestMapping("/api/v1/ai")
public class AiChatController {

	private final AiChatService aiChatService;

	public AiChatController(AiChatService aiChatService) {
		this.aiChatService = aiChatService;
	}

	@PostMapping("/chat")
	@Operation(
			summary = "Send a message to the configured AI chat model",
			description = "Returns the configured chat model's response to a plain text message.")
	@ApiResponses({
			@ApiResponse(
					responseCode = "200",
					description = "Chat response generated successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = AiChatResponse.class))),
			@ApiResponse(
					responseCode = "400",
					description = "Request validation failed or request body is malformed",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = ErrorResponse.class)))
	})
	public ResponseEntity<AiChatResponse> chat(
			@Valid @RequestBody AiChatRequest request) {
		AiChatResponse response = new AiChatResponse();
		response.setResponse(aiChatService.chat(request.getMessage()));
		return ResponseEntity.ok(response);
	}
}
