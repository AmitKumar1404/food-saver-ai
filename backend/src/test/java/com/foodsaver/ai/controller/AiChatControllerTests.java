package com.foodsaver.ai.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foodsaver.ai.service.AiChatService;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.exception.InvalidAiResponseException;

@ExtendWith(MockitoExtension.class)
class AiChatControllerTests {

	private static final String ENDPOINT = "/api/v1/ai/chat";
	private static final String MESSAGE = "How can I reduce food waste?";
	private static final String MODEL_RESPONSE = "Plan meals and store food carefully.";

	@Mock
	private AiChatService aiChatService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new AiChatController(aiChatService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void returnsModelResponseAndDelegatesToService() throws Exception {
		when(aiChatService.chat(MESSAGE)).thenReturn(MODEL_RESPONSE);

		mockMvc.perform(post(ENDPOINT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "message": "How can I reduce food waste?"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.response").value(MODEL_RESPONSE))
				.andExpect(jsonPath("$.message").doesNotExist());

		verify(aiChatService).chat(MESSAGE);
	}

	@Test
	void returnsBadRequestWhenMessageIsMissing() throws Exception {
		assertValidationError("{}");
	}

	@Test
	void returnsBadRequestWhenMessageIsBlank() throws Exception {
		assertValidationError("""
				{
				  "message": "   "
				}
				""");
	}

	@Test
	void returnsBadRequestWhenRequestBodyIsMalformed() throws Exception {
		mockMvc.perform(post(ENDPOINT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Malformed request body"))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors").isEmpty());

		verifyNoInteractions(aiChatService);
	}

	@Test
	void returnsBadGatewayWhenModelResponseIsInvalid() throws Exception {
		when(aiChatService.chat(MESSAGE))
				.thenThrow(new InvalidAiResponseException());

		mockMvc.perform(post(ENDPOINT)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "message": "How can I reduce food waste?"
						}
						"""))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502))
				.andExpect(jsonPath("$.message")
						.value("AI model returned an invalid response"))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());

		verify(aiChatService).chat(MESSAGE);
	}

	private void assertValidationError(String body) throws Exception {
		mockMvc.perform(post(ENDPOINT)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors.message[0]")
						.value("Message is required"));

		verifyNoInteractions(aiChatService);
	}
}
