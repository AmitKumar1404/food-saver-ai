package com.foodsaver.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.OrderItem;
import com.foodsaver.repository.projection.OrderCompletionTarget;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

	Optional<OrderItem> findByOrderId(Long orderId);

	@Query("""
			select new com.foodsaver.repository.projection.OrderCompletionTarget(
				item.order.id,
				item.id,
				item.order.customer.id,
				item.order.restaurant.id,
				item.product.id,
				item.inventory.id,
				item.offer.id,
				item.reservation.id)
			from OrderItem item
			where item.order.publicId = :orderPublicId
			  and item.order.customer.id = :customerId
			""")
	Optional<OrderCompletionTarget> findCompletionTarget(
			@Param("orderPublicId") UUID orderPublicId,
			@Param("customerId") Long customerId);

	List<OrderItem> findAllByOrderIdIn(Collection<Long> orderIds);

	boolean existsByReservationId(Long reservationId);
}
