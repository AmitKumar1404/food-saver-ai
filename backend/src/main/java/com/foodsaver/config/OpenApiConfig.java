package com.foodsaver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI foodSaverOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("FoodSaver AI API")
						.description(
								"REST APIs for the FoodSaver AI platform. "
										+ "The platform helps restaurants manage restaurants, "
										+ "products, inventory, surplus food, offers, and orders.")
						.version("v1.0.0"));
	}
}
