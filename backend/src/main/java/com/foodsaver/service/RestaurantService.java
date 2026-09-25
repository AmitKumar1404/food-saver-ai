package com.foodsaver.service;

import java.util.List;
import java.util.UUID;

import com.foodsaver.dto.request.RestaurantRequest;
import com.foodsaver.dto.response.RestaurantResponse;

public interface RestaurantService {

	RestaurantResponse createRestaurant(RestaurantRequest request);

	RestaurantResponse getRestaurantByPublicId(UUID publicId);

	List<RestaurantResponse> getAllRestaurants();
}
