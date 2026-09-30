package com.foodsaver.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.foodsaver.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

	Optional<OrderItem> findByOrderId(Long orderId);

	List<OrderItem> findAllByOrderIdIn(Collection<Long> orderIds);

	boolean existsByReservationId(Long reservationId);
}
