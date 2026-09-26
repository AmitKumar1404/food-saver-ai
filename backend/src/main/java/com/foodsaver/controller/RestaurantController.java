package com.foodsaver.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.RestaurantRequest;
import com.foodsaver.dto.response.RestaurantResponse;
import com.foodsaver.service.RestaurantService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/restaurants")
public class RestaurantController {

	private final RestaurantService restaurantService;

	public RestaurantController(RestaurantService restaurantService) {
		this.restaurantService = restaurantService;
	}

	@PostMapping
	public ResponseEntity<RestaurantResponse> createRestaurant(
			@Valid @RequestBody RestaurantRequest request) {
		RestaurantResponse response = restaurantService.createRestaurant(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{publicId}")
	public ResponseEntity<RestaurantResponse> getRestaurantByPublicId(
			@PathVariable("publicId") UUID publicId) {
		RestaurantResponse response = restaurantService.getRestaurantByPublicId(publicId);
		return ResponseEntity.ok(response);
	}

	@GetMapping
	public ResponseEntity<List<RestaurantResponse>> getAllRestaurants() {
		List<RestaurantResponse> responses = restaurantService.getAllRestaurants();
		return ResponseEntity.ok(responses);
	}
}
