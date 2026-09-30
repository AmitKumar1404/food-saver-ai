package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.response.OrderResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Order;
import com.foodsaver.entity.OrderItem;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.OrderStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.OrderCompletionConflictException;
import com.foodsaver.exception.OrderNotFoundException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.OrderItemRepository;
import com.foodsaver.repository.OrderRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.projection.OrderCompletionTarget;
import com.foodsaver.service.ReservationLedgerService;

@Service
class OrderCompletionCommand {

	private final CustomerRepository customerRepository;
	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final RestaurantRepository restaurantRepository;
	private final ProductRepository productRepository;
	private final InventoryRepository inventoryRepository;
	private final OfferRepository offerRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationLedgerService reservationLedgerService;
	private final OrderResponseMapper responseMapper;
	private final OrderCompletionTransactionObserver transactionObserver;

	OrderCompletionCommand(
			CustomerRepository customerRepository,
			OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			RestaurantRepository restaurantRepository,
			ProductRepository productRepository,
			InventoryRepository inventoryRepository,
			OfferRepository offerRepository,
			ReservationRepository reservationRepository,
			ReservationLedgerService reservationLedgerService,
			OrderResponseMapper responseMapper,
			OrderCompletionTransactionObserver transactionObserver) {
		this.customerRepository = customerRepository;
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.restaurantRepository = restaurantRepository;
		this.productRepository = productRepository;
		this.inventoryRepository = inventoryRepository;
		this.offerRepository = offerRepository;
		this.reservationRepository = reservationRepository;
		this.reservationLedgerService = reservationLedgerService;
		this.responseMapper = responseMapper;
		this.transactionObserver = transactionObserver;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	OrderResponse complete(OrderCompletionRequest request) {
		Customer customer = customerRepository
				.findByPublicIdForAllocation(request.customerPublicId())
				.orElseThrow(() -> new CustomerNotFoundException(
						request.customerPublicId()));
		OrderCompletionTarget target = orderItemRepository
				.findCompletionTarget(
						request.orderPublicId(),
						customer.getId())
				.orElseGet(() -> {
					if (orderRepository.findByPublicIdAndCustomerPublicId(
							request.orderPublicId(),
							request.customerPublicId()).isPresent()) {
						throw completionConflict();
					}
					throw new OrderNotFoundException(request.orderPublicId());
				});
		Order order = orderRepository
				.findByIdAndCustomerIdForCompletion(
						target.orderId(),
						customer.getId())
				.orElseThrow(() -> new OrderNotFoundException(
						request.orderPublicId()));
		if (order.getStatus() == OrderStatus.COMPLETED) {
			return mapCompletedReplay(order, target);
		}

		Restaurant restaurant = restaurantRepository
				.findByIdForAllocation(target.restaurantId())
				.orElseThrow(this::completionConflict);
		Product product = productRepository
				.findByIdAndRestaurantIdForAllocation(
						target.productId(),
						target.restaurantId())
				.orElseThrow(this::completionConflict);
		transactionObserver.beforeInventoryLock(request.orderPublicId());
		Inventory inventory = inventoryRepository
				.findByIdAndRestaurantId(
						target.inventoryId(),
						target.restaurantId())
				.orElseThrow(this::completionConflict);
		transactionObserver.afterInventoryLock(request.orderPublicId());
		List<Offer> offers = offerRepository.findAllByIdInOrderByIdForAllocation(
				List.of(target.offerId()));
		List<Reservation> reservations =
				reservationRepository.findAllByIdInOrderByIdForAllocation(
						List.of(target.reservationId()));
		Instant transactionTime = Reservation.normalizeTimestamp(Instant.now());

		Offer offer = requireSingleOffer(offers, target);
		Reservation reservation = requireSingleReservation(reservations, target);
		OrderItem item = orderItemRepository.findById(target.orderItemId())
				.orElseThrow(this::completionConflict);
		validateLockedState(
				customer,
				order,
				item,
				restaurant,
				product,
				inventory,
				offer,
				reservation,
				target);
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		InventorySnapshot before = InventorySnapshot.of(inventory);
		BigDecimal quantity = item.getQuantity();
		if (inventory.getReservedQuantity().compareTo(quantity) < 0) {
			throw completionConflict();
		}

		inventory.setReservedQuantity(
				inventory.getReservedQuantity().subtract(quantity));
		inventory.setSoldQuantity(
				inventory.getSoldQuantity().add(quantity));
		transactionObserver.afterInventoryUpdate(
				request.orderPublicId(),
				inventory);
		try {
			order.complete(transactionTime);
		} catch (IllegalArgumentException | IllegalStateException exception) {
			throw new OrderCompletionConflictException(
					"Order completion conflicts with current Order state",
					exception);
		}
		transactionObserver.afterOrderCompletion(
				request.orderPublicId(),
				order);
		orderRepository.flush();
		inventoryRepository.flush();
		transactionObserver.afterFlush(request.orderPublicId());

		transactionObserver.beforePostMutationValidation(
				request.orderPublicId(),
				inventory,
				order);
		verifyExpectedMovement(before, inventory, quantity);
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		transactionObserver.beforeFinalVerification(
				request.orderPublicId(),
				inventory,
				order);
		verifyPersistence(order, reservation, transactionTime);
		return responseMapper.toResponse(order, item);
	}

	private OrderResponse mapCompletedReplay(
			Order order,
			OrderCompletionTarget target) {
		OrderItem item = orderItemRepository.findByOrderId(order.getId())
				.orElseThrow(this::completionConflict);
		if (order.getCompletedAt() == null
				|| !sameId(item.getId(), target.orderItemId())
				|| item.getOrder() == null
				|| !sameId(item.getOrder().getId(), order.getId())) {
			throw completionConflict();
		}
		return responseMapper.toResponse(order, item);
	}

	private Offer requireSingleOffer(
			List<Offer> offers,
			OrderCompletionTarget target) {
		if (offers.size() != 1
				|| !sameId(offers.getFirst().getId(), target.offerId())) {
			throw completionConflict();
		}
		return offers.getFirst();
	}

	private Reservation requireSingleReservation(
			List<Reservation> reservations,
			OrderCompletionTarget target) {
		if (reservations.size() != 1
				|| !sameId(
						reservations.getFirst().getId(),
						target.reservationId())) {
			throw completionConflict();
		}
		return reservations.getFirst();
	}

	private void validateLockedState(
			Customer customer,
			Order order,
			OrderItem item,
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer,
			Reservation reservation,
			OrderCompletionTarget target) {
		boolean mismatch = order.getStatus() != OrderStatus.CONFIRMED
				|| order.getCompletedAt() != null
				|| !sameId(order.getId(), target.orderId())
				|| order.getCustomer() == null
				|| !sameId(order.getCustomer().getId(), customer.getId())
				|| order.getRestaurant() == null
				|| !sameId(order.getRestaurant().getId(), restaurant.getId())
				|| !sameId(item.getId(), target.orderItemId())
				|| item.getOrder() == null
				|| !sameId(item.getOrder().getId(), order.getId())
				|| item.getReservation() == null
				|| !sameId(item.getReservation().getId(), reservation.getId())
				|| item.getOffer() == null
				|| !sameId(item.getOffer().getId(), offer.getId())
				|| item.getProduct() == null
				|| !sameId(item.getProduct().getId(), product.getId())
				|| item.getInventory() == null
				|| !sameId(item.getInventory().getId(), inventory.getId())
				|| reservation.getStatus() != ReservationStatus.CONVERTED
				|| reservation.getInventory() == null
				|| !sameId(reservation.getInventory().getId(), inventory.getId())
				|| reservation.getOffer() == null
				|| !sameId(reservation.getOffer().getId(), offer.getId())
				|| reservation.getRestaurant() == null
				|| !sameId(
						reservation.getRestaurant().getId(),
						restaurant.getId())
				|| reservation.getCustomer() == null
				|| !sameId(reservation.getCustomer().getId(), customer.getId())
				|| offer.getRestaurant() == null
				|| !sameId(offer.getRestaurant().getId(), restaurant.getId())
				|| offer.getProduct() == null
				|| !sameId(offer.getProduct().getId(), product.getId())
				|| offer.getInventory() == null
				|| !sameId(offer.getInventory().getId(), inventory.getId())
				|| product.getRestaurant() == null
				|| !sameId(product.getRestaurant().getId(), restaurant.getId())
				|| inventory.getRestaurant() == null
				|| !sameId(inventory.getRestaurant().getId(), restaurant.getId())
				|| inventory.getProduct() == null
				|| !sameId(inventory.getProduct().getId(), product.getId());
		if (mismatch) {
			throw completionConflict();
		}
		validateSnapshots(order, item, reservation);
	}

	private void validateSnapshots(
			Order order,
			OrderItem item,
			Reservation reservation) {
		BigDecimal quantity = item.getQuantity();
		BigDecimal unitPrice = item.getUnitPrice();
		BigDecimal total = item.getTotalAmount();
		String currency = item.getCurrencyCode();
		if (quantity == null
				|| quantity.compareTo(BigDecimal.ZERO) <= 0
				|| reservation.getQuantity() == null
				|| quantity.compareTo(reservation.getQuantity()) != 0
				|| unitPrice == null
				|| reservation.getUnitPrice() == null
				|| unitPrice.compareTo(reservation.getUnitPrice()) != 0
				|| total == null
				|| reservation.getTotalAmount() == null
				|| total.compareTo(reservation.getTotalAmount()) != 0
				|| order.getTotalAmount() == null
				|| total.compareTo(order.getTotalAmount()) != 0
				|| currency == null
				|| !currency.equals(reservation.getCurrencyCode())
				|| !currency.equals(order.getCurrencyCode())) {
			throw completionConflict();
		}
	}

	private void verifyInventoryEquation(Inventory inventory) {
		BigDecimal prepared = inventory.getPreparedQuantity();
		BigDecimal available = inventory.getAvailableQuantity();
		BigDecimal reserved = inventory.getReservedQuantity();
		BigDecimal sold = inventory.getSoldQuantity();
		if (prepared == null
				|| available == null
				|| reserved == null
				|| sold == null
				|| prepared.compareTo(BigDecimal.ZERO) < 0
				|| available.compareTo(BigDecimal.ZERO) < 0
				|| reserved.compareTo(BigDecimal.ZERO) < 0
				|| sold.compareTo(BigDecimal.ZERO) < 0
				|| prepared.compareTo(
						available.add(reserved).add(sold)) != 0) {
			throw completionConflict();
		}
	}

	private void verifyReservationLedger(Inventory inventory) {
		if (reservationLedgerService.hasOrphanConvertedReservations(
				inventory.getId())) {
			throw completionConflict();
		}
		BigDecimal outstanding =
				reservationLedgerService.outstandingQuantity(inventory.getId());
		if (outstanding == null
				|| inventory.getReservedQuantity().compareTo(outstanding) != 0) {
			throw completionConflict();
		}
	}

	private void verifyExpectedMovement(
			InventorySnapshot before,
			Inventory inventory,
			BigDecimal quantity) {
		if (before.prepared().compareTo(inventory.getPreparedQuantity()) != 0
				|| before.available().compareTo(
						inventory.getAvailableQuantity()) != 0
				|| before.reserved().subtract(quantity).compareTo(
						inventory.getReservedQuantity()) != 0
				|| before.sold().add(quantity).compareTo(
						inventory.getSoldQuantity()) != 0
				|| before.status() != inventory.getStatus()) {
			throw completionConflict();
		}
	}

	private void verifyPersistence(
			Order order,
			Reservation reservation,
			Instant transactionTime) {
		if (order.getStatus() != OrderStatus.COMPLETED
				|| order.getCompletedAt() == null
				|| !order.getCompletedAt().equals(transactionTime)
				|| !order.getUpdatedAt().equals(transactionTime)
				|| reservation.getStatus() != ReservationStatus.CONVERTED) {
			throw completionConflict();
		}
	}

	private boolean sameId(Long first, Long second) {
		return first != null && Objects.equals(first, second);
	}

	private OrderCompletionConflictException completionConflict() {
		return new OrderCompletionConflictException(
				"Order completion conflicts with current persisted state");
	}

	record OrderCompletionRequest(
			java.util.UUID customerPublicId,
			java.util.UUID orderPublicId) {
	}

	private record InventorySnapshot(
			BigDecimal prepared,
			BigDecimal available,
			BigDecimal reserved,
			BigDecimal sold,
			com.foodsaver.enums.InventoryStatus status) {

		static InventorySnapshot of(Inventory inventory) {
			return new InventorySnapshot(
					inventory.getPreparedQuantity(),
					inventory.getAvailableQuantity(),
					inventory.getReservedQuantity(),
					inventory.getSoldQuantity(),
					inventory.getStatus());
		}
	}
}
