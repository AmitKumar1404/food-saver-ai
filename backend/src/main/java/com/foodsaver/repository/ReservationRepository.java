package com.foodsaver.repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.foodsaver.entity.Reservation;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.repository.projection.InventoryReservationLedgerTotal;
import com.foodsaver.repository.projection.OrderConversionTarget;
import com.foodsaver.repository.projection.ReservationAllocationTarget;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	Optional<Reservation> findByPublicId(UUID publicId);

	Optional<Reservation> findByPublicIdAndCustomerId(
			UUID publicId,
			Long customerId);

	Optional<Reservation> findByCustomerIdAndIdempotencyKey(
			Long customerId,
			String idempotencyKey);

	Optional<Reservation> findByCustomerPublicIdAndIdempotencyKey(
			UUID customerPublicId,
			String idempotencyKey);

	@Query("""
			select new com.foodsaver.repository.projection.OrderConversionTarget(
				reservation.id,
				reservation.customer.id,
				reservation.restaurant.id,
				reservation.offer.product.id,
				reservation.inventory.id,
				reservation.offer.id)
			from Reservation reservation
			where reservation.publicId = :reservationPublicId
			  and reservation.customer.id = :customerId
			""")
	Optional<OrderConversionTarget> findOrderConversionTarget(
			@Param("reservationPublicId") UUID reservationPublicId,
			@Param("customerId") Long customerId);

	@Query("""
			select new com.foodsaver.repository.projection.ReservationAllocationTarget(
				reservation.id,
				reservation.offer.id,
				reservation.expiresAt,
				reservation.quantity)
			from Reservation reservation
			where reservation.inventory.id = :inventoryId
			  and reservation.status = :status
			order by reservation.id
			""")
	List<ReservationAllocationTarget> findAllocationTargetsByInventoryIdAndStatus(
			@Param("inventoryId") Long inventoryId,
			@Param("status") ReservationStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select reservation
			from Reservation reservation
			where reservation.id in :reservationIds
			order by reservation.id
			""")
	List<Reservation> findAllByIdInOrderByIdForAllocation(
			@Param("reservationIds") Collection<Long> reservationIds);

	@Query("""
			select coalesce(sum(reservation.quantity), 0)
			from Reservation reservation
			where reservation.inventory.id = :inventoryId
			  and reservation.status in :statuses
			""")
	BigDecimal sumQuantityByInventoryIdAndStatusIn(
			@Param("inventoryId") Long inventoryId,
			@Param("statuses") Collection<ReservationStatus> statuses);

	@Query("""
			select coalesce(sum(reservation.quantity), 0)
			from Reservation reservation
			where reservation.inventory.id = :inventoryId
			  and (
				reservation.status =
					com.foodsaver.enums.ReservationStatus.ACTIVE
				or (
					reservation.status =
						com.foodsaver.enums.ReservationStatus.CONVERTED
					and exists (
						select item.id
						from OrderItem item
						where item.reservation.id = reservation.id
						  and item.order.status =
							com.foodsaver.enums.OrderStatus.CONFIRMED
					)
				)
			  )
			""")
	BigDecimal sumOutstandingQuantityByInventoryId(
			@Param("inventoryId") Long inventoryId);

	@Query("""
			select new com.foodsaver.repository.projection.InventoryReservationLedgerTotal(
				reservation.inventory.id,
				sum(reservation.quantity))
			from Reservation reservation
			where reservation.status =
					com.foodsaver.enums.ReservationStatus.ACTIVE
			   or (
					reservation.status =
						com.foodsaver.enums.ReservationStatus.CONVERTED
					and exists (
						select item.id
						from OrderItem item
						where item.reservation.id = reservation.id
						  and item.order.status =
							com.foodsaver.enums.OrderStatus.CONFIRMED
					)
			   )
			group by reservation.inventory.id
			""")
	List<InventoryReservationLedgerTotal> sumOutstandingQuantityByInventory();

	@Query("""
			select count(reservation)
			from Reservation reservation
			where reservation.status =
					com.foodsaver.enums.ReservationStatus.CONVERTED
			  and not exists (
				select item.id
				from OrderItem item
				where item.reservation.id = reservation.id
				  and item.order.status in (
					com.foodsaver.enums.OrderStatus.CONFIRMED,
					com.foodsaver.enums.OrderStatus.COMPLETED)
			  )
			""")
	long countOrphanConvertedReservations();

	@Query("""
			select count(reservation)
			from Reservation reservation
			where reservation.inventory.id = :inventoryId
			  and reservation.status =
					com.foodsaver.enums.ReservationStatus.CONVERTED
			  and not exists (
				select item.id
				from OrderItem item
				where item.reservation.id = reservation.id
				  and item.order.status in (
					com.foodsaver.enums.OrderStatus.CONFIRMED,
					com.foodsaver.enums.OrderStatus.COMPLETED)
			  )
			""")
	long countOrphanConvertedReservationsByInventoryId(
			@Param("inventoryId") Long inventoryId);

	@Query("""
			select coalesce(sum(reservation.quantity), 0)
			from Reservation reservation
			where reservation.offer.id = :offerId
			  and reservation.status in :statuses
			""")
	BigDecimal sumQuantityByOfferIdAndStatusIn(
			@Param("offerId") Long offerId,
			@Param("statuses") Collection<ReservationStatus> statuses);

	long countByStatusIn(Collection<ReservationStatus> statuses);

	boolean existsByCustomerIdAndIdempotencyKey(
			Long customerId,
			String idempotencyKey);
}
