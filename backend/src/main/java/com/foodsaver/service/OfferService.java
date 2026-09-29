package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.OfferCreateRequest;
import com.foodsaver.dto.response.OfferResponse;

public interface OfferService {

	OfferResponse createOffer(
			UUID restaurantPublicId,
			OfferCreateRequest request);
}
