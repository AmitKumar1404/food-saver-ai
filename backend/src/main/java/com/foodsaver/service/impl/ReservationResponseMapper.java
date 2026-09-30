package com.foodsaver.service.impl;

import org.springframework.stereotype.Component;

import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Reservation;

@Component
class ReservationResponseMapper {

	ReservationResponse toResponse(Reservation reservation) {
		ReservationResponse response = new ReservationResponse();
		response.setPublicId(reservation.getPublicId());
		response.setCustomerPublicId(reservation.getCustomer().getPublicId());
		response.setRestaurantPublicId(reservation.getRestaurant().getPublicId());
		response.setOfferPublicId(reservation.getOffer().getPublicId());
		response.setInventoryPublicId(reservation.getInventory().getPublicId());
		response.setQuantity(reservation.getQuantity());
		response.setUnitPrice(reservation.getUnitPrice());
		response.setTotalAmount(reservation.getTotalAmount());
		response.setCurrencyCode(reservation.getCurrencyCode());
		response.setStatus(reservation.getStatus());
		response.setExpiresAt(reservation.getExpiresAt());
		response.setCreatedAt(reservation.getCreatedAt());
		response.setUpdatedAt(reservation.getUpdatedAt());
		return response;
	}
}
