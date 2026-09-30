package com.foodsaver.ai.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import com.foodsaver.exception.InvalidAiResponseException;

@ExtendWith(MockitoExtension.class)
class AiChatServiceImplTests {

	private static final String MESSAGE = "How can I reduce food waste?";
	private static final String MODEL_RESPONSE = "Plan meals and store food carefully.";

	@Mock
	private ChatClient chatClient;

	@Mock
	private ChatClient.ChatClientRequestSpec requestSpec;

	@Mock
	private ChatClient.CallResponseSpec responseSpec;

	private AiChatServiceImpl aiChatService;

	@BeforeEach
	void setUp() {
		aiChatService = new AiChatServiceImpl(chatClient);
	}

	@Test
	void sendsMessageThroughChatClientAndReturnsModelResponse() {
		arrangeModelContent(MODEL_RESPONSE);

		String response = aiChatService.chat(MESSAGE);

		assertEquals(MODEL_RESPONSE, response);
		verify(chatClient).prompt();
		verify(requestSpec).user(MESSAGE);
		verify(requestSpec).call();
		verify(responseSpec).content();
	}

	@Test
	void rejectsNullModelContent() {
		arrangeModelContent(null);

		assertThrows(
				InvalidAiResponseException.class,
				() -> aiChatService.chat(MESSAGE));
	}

	@Test
	void rejectsEmptyModelContent() {
		arrangeModelContent("");

		assertThrows(
				InvalidAiResponseException.class,
				() -> aiChatService.chat(MESSAGE));
	}

	@Test
	void rejectsWhitespaceOnlyModelContent() {
		arrangeModelContent(" \t\n");

		assertThrows(
				InvalidAiResponseException.class,
				() -> aiChatService.chat(MESSAGE));
	}

	@Test
	void propagatesProviderException() {
		IllegalStateException providerException =
				new IllegalStateException("Provider unavailable");
		when(chatClient.prompt()).thenThrow(providerException);

		IllegalStateException thrown = assertThrows(
				IllegalStateException.class,
				() -> aiChatService.chat(MESSAGE));

		assertSame(providerException, thrown);
	}

	private void arrangeModelContent(String content) {
		when(chatClient.prompt()).thenReturn(requestSpec);
		when(requestSpec.user(MESSAGE)).thenReturn(requestSpec);
		when(requestSpec.call()).thenReturn(responseSpec);
		when(responseSpec.content()).thenReturn(content);
	}
}
