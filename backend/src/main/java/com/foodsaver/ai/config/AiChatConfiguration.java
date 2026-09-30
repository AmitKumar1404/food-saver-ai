package com.foodsaver.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class AiChatConfiguration {

	@Bean
	public ChatClient chatClient(ChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}
}
