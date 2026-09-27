package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.ProductCreateRequest;
import com.foodsaver.dto.request.ProductUpdateRequest;
import com.foodsaver.dto.response.ProductResponse;

public interface ProductService {

	ProductResponse createProduct(
			UUID restaurantPublicId,
			ProductCreateRequest request);

	ProductResponse getProduct(
			UUID restaurantPublicId,
			UUID productPublicId);

	ProductResponse updateProduct(
			UUID restaurantPublicId,
			UUID productPublicId,
			ProductUpdateRequest request);

	void deleteProduct(
			UUID restaurantPublicId,
			UUID productPublicId);
}
