package com.foodsaver.service.impl;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.ProductCreateRequest;
import com.foodsaver.dto.request.ProductUpdateRequest;
import com.foodsaver.dto.response.ProductResponse;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.exception.ProductNotFoundException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.service.ProductService;

@Service
public class ProductServiceImpl implements ProductService {

	private final ProductRepository productRepository;
	private final RestaurantRepository restaurantRepository;

	public ProductServiceImpl(
			ProductRepository productRepository,
			RestaurantRepository restaurantRepository) {
		this.productRepository = productRepository;
		this.restaurantRepository = restaurantRepository;
	}

	@Override
	@Transactional
	public ProductResponse createProduct(
			UUID restaurantPublicId,
			ProductCreateRequest request) {
		Restaurant restaurant = findRestaurant(restaurantPublicId);

		Product product = new Product();
		product.setRestaurant(restaurant);
		product.setName(request.getName());
		product.setDescription(request.getDescription());
		product.setCategory(request.getCategory());
		product.setBasePrice(request.getBasePrice());
		product.setCurrencyCode(request.getCurrencyCode());

		Product savedProduct = productRepository.save(product);
		return toResponse(savedProduct);
	}

	@Override
	@Transactional(readOnly = true)
	public ProductResponse getProduct(
			UUID restaurantPublicId,
			UUID productPublicId) {
		Restaurant restaurant = findRestaurant(restaurantPublicId);

		Product product = productRepository
				.findByPublicIdAndRestaurantId(productPublicId, restaurant.getId())
				.orElseThrow(() -> new ProductNotFoundException(
						"Product not found: " + productPublicId
								+ " for restaurant: " + restaurantPublicId));

		return toResponse(product);
	}

	@Override
	@Transactional
	public ProductResponse updateProduct(
			UUID restaurantPublicId,
			UUID productPublicId,
			ProductUpdateRequest request) {
		Restaurant restaurant = findRestaurant(restaurantPublicId);

		Product product = productRepository
				.findByPublicIdAndRestaurantId(productPublicId, restaurant.getId())
				.orElseThrow(() -> new ProductNotFoundException(
						"Product not found: " + productPublicId
								+ " for restaurant: " + restaurantPublicId));

		product.setName(request.getName());
		product.setDescription(request.getDescription());
		product.setCategory(request.getCategory());
		product.setBasePrice(request.getBasePrice());
		product.setCurrencyCode(request.getCurrencyCode());

		Product savedProduct = productRepository.save(product);
		return toResponse(savedProduct);
	}

	@Override
	@Transactional
	public void deleteProduct(
			UUID restaurantPublicId,
			UUID productPublicId) {
		Restaurant restaurant = findRestaurant(restaurantPublicId);

		Product product = productRepository
				.findByPublicIdAndRestaurantId(productPublicId, restaurant.getId())
				.orElseThrow(() -> new ProductNotFoundException(
						"Product not found: " + productPublicId
								+ " for restaurant: " + restaurantPublicId));

		productRepository.delete(product);
	}

	private Restaurant findRestaurant(UUID restaurantPublicId) {
		return restaurantRepository.findByPublicId(restaurantPublicId)
				.orElseThrow(() -> new RestaurantNotFoundException(restaurantPublicId));
	}

	private ProductResponse toResponse(Product product) {
		ProductResponse response = new ProductResponse();
		response.setPublicId(product.getPublicId());
		response.setRestaurantPublicId(product.getRestaurant().getPublicId());
		response.setName(product.getName());
		response.setDescription(product.getDescription());
		response.setCategory(product.getCategory());
		response.setBasePrice(product.getBasePrice());
		response.setCurrencyCode(product.getCurrencyCode());
		response.setStatus(product.getStatus());
		response.setCreatedAt(product.getCreatedAt());
		response.setUpdatedAt(product.getUpdatedAt());
		return response;
	}
}
