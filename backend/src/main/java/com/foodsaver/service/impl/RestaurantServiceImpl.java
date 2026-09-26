package com.foodsaver.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.RestaurantRequest;
import com.foodsaver.dto.response.RestaurantResponse;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.service.RestaurantService;

@Service
@Transactional(readOnly = true)
public class RestaurantServiceImpl implements RestaurantService {

	private final RestaurantRepository restaurantRepository;

	public RestaurantServiceImpl(RestaurantRepository restaurantRepository) {
		this.restaurantRepository = restaurantRepository;
	}

	@Override
	@Transactional
	public RestaurantResponse createRestaurant(RestaurantRequest request) {
		Restaurant restaurant = toEntity(request);
		restaurant.setStatus(RestaurantStatus.PENDING_VERIFICATION);

		Restaurant savedRestaurant = restaurantRepository.save(restaurant);
		return toResponse(savedRestaurant);
	}

	@Override
	public RestaurantResponse getRestaurantByPublicId(UUID publicId) {
		return restaurantRepository.findByPublicId(publicId)
				.map(this::toResponse)
				.orElseThrow(() -> new RestaurantNotFoundException(publicId));
	}

	@Override
	public List<RestaurantResponse> getAllRestaurants() {
		return restaurantRepository.findAll()
				.stream()
				.map(this::toResponse)
				.toList();
	}

	private Restaurant toEntity(RestaurantRequest request) {
		Restaurant restaurant = new Restaurant();
		restaurant.setName(request.getName());
		restaurant.setLegalName(request.getLegalName());
		restaurant.setBusinessType(request.getBusinessType());
		restaurant.setDescription(request.getDescription());
		restaurant.setContactEmail(request.getContactEmail());
		restaurant.setContactPhone(request.getContactPhone());
		restaurant.setWebsiteUrl(request.getWebsiteUrl());
		restaurant.setAddressLine1(request.getAddressLine1());
		restaurant.setAddressLine2(request.getAddressLine2());
		restaurant.setCity(request.getCity());
		restaurant.setStateProvince(request.getStateProvince());
		restaurant.setPostalCode(request.getPostalCode());
		restaurant.setCountryCode(request.getCountryCode());
		restaurant.setLatitude(request.getLatitude());
		restaurant.setLongitude(request.getLongitude());
		restaurant.setTimezone(request.getTimezone());
		restaurant.setCurrencyCode(request.getCurrencyCode());
		restaurant.setPickupInstructions(request.getPickupInstructions());
		return restaurant;
	}

	private RestaurantResponse toResponse(Restaurant restaurant) {
		RestaurantResponse response = new RestaurantResponse();
		response.setPublicId(restaurant.getPublicId());
		response.setName(restaurant.getName());
		response.setLegalName(restaurant.getLegalName());
		response.setBusinessType(restaurant.getBusinessType());
		response.setDescription(restaurant.getDescription());
		response.setContactEmail(restaurant.getContactEmail());
		response.setContactPhone(restaurant.getContactPhone());
		response.setWebsiteUrl(restaurant.getWebsiteUrl());
		response.setAddressLine1(restaurant.getAddressLine1());
		response.setAddressLine2(restaurant.getAddressLine2());
		response.setCity(restaurant.getCity());
		response.setStateProvince(restaurant.getStateProvince());
		response.setPostalCode(restaurant.getPostalCode());
		response.setCountryCode(restaurant.getCountryCode());
		response.setLatitude(restaurant.getLatitude());
		response.setLongitude(restaurant.getLongitude());
		response.setTimezone(restaurant.getTimezone());
		response.setCurrencyCode(restaurant.getCurrencyCode());
		response.setPickupInstructions(restaurant.getPickupInstructions());
		response.setStatus(restaurant.getStatus());
		response.setCreatedAt(restaurant.getCreatedAt());
		response.setUpdatedAt(restaurant.getUpdatedAt());
		return response;
	}
}
