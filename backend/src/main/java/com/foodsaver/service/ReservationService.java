package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;

public interface ReservationService {

	ReservationResponse createReservation(
			UUID customerPublicId,
			ReservationCreateRequest request,
			String idempotencyKey);
}
