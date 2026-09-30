package com.foodsaver.service.impl;

import org.springframework.stereotype.Component;

import com.foodsaver.dto.response.OrderItemResponse;
import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.entity.Order;
import com.foodsaver.entity.OrderItem;

@Component
class OrderResponseMapper {

	OrderResponse toResponse(Order order, OrderItem item) {
		OrderItemResponse itemResponse = new OrderItemResponse();
		itemResponse.setPublicId(item.getPublicId());
		itemResponse.setReservationPublicId(
				item.getReservation().getPublicId());
		itemResponse.setOfferPublicId(item.getOffer().getPublicId());
		itemResponse.setProductPublicId(item.getProduct().getPublicId());
		itemResponse.setInventoryPublicId(item.getInventory().getPublicId());
		itemResponse.setProductNameSnapshot(item.getProductNameSnapshot());
		itemResponse.setQuantity(item.getQuantity());
		itemResponse.setUnitPrice(item.getUnitPrice());
		itemResponse.setTotalAmount(item.getTotalAmount());
		itemResponse.setCurrencyCode(item.getCurrencyCode());
		itemResponse.setCreatedAt(item.getCreatedAt());

		OrderResponse response = new OrderResponse();
		response.setPublicId(order.getPublicId());
		response.setCustomerPublicId(order.getCustomer().getPublicId());
		response.setRestaurantPublicId(order.getRestaurant().getPublicId());
		response.setStatus(order.getStatus());
		response.setTotalAmount(order.getTotalAmount());
		response.setCurrencyCode(order.getCurrencyCode());
		response.setConfirmedAt(order.getConfirmedAt());
		response.setCreatedAt(order.getCreatedAt());
		response.setUpdatedAt(order.getUpdatedAt());
		response.setItem(itemResponse);
		return response;
	}
}
