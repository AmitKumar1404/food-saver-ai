package com.foodsaver.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foodsaver.dto.request.ProductCreateRequest;
import com.foodsaver.dto.request.ProductUpdateRequest;
import com.foodsaver.dto.response.ProductResponse;
import com.foodsaver.service.ProductService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/restaurants/{restaurantPublicId}/products")
public class ProductController {

	private final ProductService productService;

	public ProductController(ProductService productService) {
		this.productService = productService;
	}

	@PostMapping
	public ResponseEntity<ProductResponse> createProduct(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@Valid @RequestBody ProductCreateRequest request) {
		ProductResponse response = productService.createProduct(restaurantPublicId, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{productPublicId}")
	public ResponseEntity<ProductResponse> getProduct(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@PathVariable("productPublicId") UUID productPublicId) {
		ProductResponse response = productService.getProduct(
				restaurantPublicId,
				productPublicId);
		return ResponseEntity.ok(response);
	}

	@PutMapping("/{productPublicId}")
	public ResponseEntity<ProductResponse> updateProduct(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@PathVariable("productPublicId") UUID productPublicId,
			@Valid @RequestBody ProductUpdateRequest request) {
		ProductResponse response = productService.updateProduct(
				restaurantPublicId,
				productPublicId,
				request);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{productPublicId}")
	public ResponseEntity<Void> deleteProduct(
			@PathVariable("restaurantPublicId") UUID restaurantPublicId,
			@PathVariable("productPublicId") UUID productPublicId) {
		productService.deleteProduct(restaurantPublicId, productPublicId);
		return ResponseEntity.noContent().build();
	}
}
