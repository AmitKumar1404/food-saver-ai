package com.foodsaver.ai.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.foodsaver.ai.service.AiChatService;
import com.foodsaver.exception.InvalidAiResponseException;

@Service
@Profile("local")
public class AiChatServiceImpl implements AiChatService {

	private final ChatClient chatClient;

	public AiChatServiceImpl(ChatClient chatClient) {
		this.chatClient = chatClient;
	}

	@Override
	public String chat(String message) {
		String response = chatClient.prompt()
				.user(message)
				.call()
				.content();
		if (response == null || response.isBlank()) {
			throw new InvalidAiResponseException();
		}
		return response;
	}
}
