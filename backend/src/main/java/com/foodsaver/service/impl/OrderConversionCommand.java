package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.OrderConversionConflictException;
import com.foodsaver.exception.OrderIdempotencyConflictException;
import com.foodsaver.exception.OrderIdempotencyRaceException;
import com.foodsaver.exception.ReservationNotFoundException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.OrderItemRepository;
import com.foodsaver.repository.OrderRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.projection.OrderConversionTarget;

@Service
class OrderConversionCommand {

	private static final String IDEMPOTENCY_CONSTRAINT =
			"uk_customer_orders_customer_idempotency";
	private static final String RESERVATION_CONSTRAINT =
			"uk_order_items_reservation";
	private static final Set<ReservationStatus> ALLOCATED_STATUSES =
			Set.of(ReservationStatus.ACTIVE, ReservationStatus.CONVERTED);

	private final CustomerRepository customerRepository;
	private final RestaurantRepository restaurantRepository;
	private final ProductRepository productRepository;
	private final InventoryRepository inventoryRepository;
	private final OfferRepository offerRepository;
	private final ReservationRepository reservationRepository;
	private final OrderRepository orderRepository;
	private final OrderItemRepository orderItemRepository;
	private final OrderResponseMapper responseMapper;
	private final OrderConversionTransactionObserver transactionObserver;

	OrderConversionCommand(
			CustomerRepository customerRepository,
			RestaurantRepository restaurantRepository,
			ProductRepository productRepository,
			InventoryRepository inventoryRepository,
			OfferRepository offerRepository,
			ReservationRepository reservationRepository,
			OrderRepository orderRepository,
			OrderItemRepository orderItemRepository,
			OrderResponseMapper responseMapper,
			OrderConversionTransactionObserver transactionObserver) {
		this.customerRepository = customerRepository;
		this.restaurantRepository = restaurantRepository;
		this.productRepository = productRepository;
		this.inventoryRepository = inventoryRepository;
		this.offerRepository = offerRepository;
		this.reservationRepository = reservationRepository;
		this.orderRepository = orderRepository;
		this.orderItemRepository = orderItemRepository;
		this.responseMapper = responseMapper;
		this.transactionObserver = transactionObserver;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	OrderResponse convert(OrderConversionRequest request) {
		Customer customer = customerRepository
				.findByPublicIdForAllocation(request.customerPublicId())
				.orElseThrow(() -> new CustomerNotFoundException(
						request.customerPublicId()));
		validateCustomer(customer);

		Order existing = orderRepository
				.findByCustomerIdAndIdempotencyKey(
						customer.getId(),
						request.idempotencyKey())
				.orElse(null);
		if (existing != null) {
			validateReplayHash(existing, request.requestHash());
			return mapExisting(existing);
		}

		OrderConversionTarget target = reservationRepository
				.findOrderConversionTarget(
						request.reservationPublicId(),
						customer.getId())
				.orElseThrow(() -> new ReservationNotFoundException(
						request.reservationPublicId()));
		Restaurant restaurant = restaurantRepository
				.findByIdForAllocation(target.restaurantId())
				.orElseThrow(this::conversionConflict);
		Product product = productRepository
				.findByIdAndRestaurantIdForAllocation(
						target.productId(),
						target.restaurantId())
				.orElseThrow(this::conversionConflict);
		transactionObserver.beforeInventoryLock(request.idempotencyKey());
		Inventory inventory = inventoryRepository
				.findByIdAndRestaurantId(
						target.inventoryId(),
						target.restaurantId())
				.orElseThrow(this::conversionConflict);
		transactionObserver.afterInventoryLock(request.idempotencyKey());
		List<Offer> offers = offerRepository.findAllByIdInOrderByIdForAllocation(
				List.of(target.offerId()));
		List<Reservation> reservations =
				reservationRepository.findAllByIdInOrderByIdForAllocation(
						List.of(target.reservationId()));
		Instant transactionTime = Reservation.normalizeTimestamp(Instant.now());

		Offer offer = requireSingleOffer(offers, target);
		Reservation reservation = requireSingleReservation(reservations, target);
		validateLockedState(
				customer,
				restaurant,
				product,
				inventory,
				offer,
				reservation,
				target,
				transactionTime);
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		InventorySnapshot inventorySnapshot = InventorySnapshot.of(inventory);

		Order order = new Order(
				customer,
				restaurant,
				reservation.getTotalAmount(),
				reservation.getCurrencyCode(),
				request.idempotencyKey(),
				request.requestHash(),
				transactionTime);
		Order savedOrder = saveOrder(order);
		transactionObserver.afterOrderPersistence(request.idempotencyKey());

		OrderItem item = new OrderItem(
				savedOrder,
				reservation,
				offer,
				product,
				inventory,
				product.getName(),
				reservation.getQuantity(),
				reservation.getUnitPrice(),
				reservation.getTotalAmount(),
				reservation.getCurrencyCode(),
				transactionTime);
		OrderItem savedItem = saveOrderItem(item);
		transactionObserver.afterOrderItemPersistence(request.idempotencyKey());

		reservation.setStatus(ReservationStatus.CONVERTED);
		reservation.setConvertedAt(transactionTime);
		reservationRepository.flush();
		transactionObserver.afterReservationConversion(
				request.idempotencyKey(),
				inventory);
		inventorySnapshot.verifyUnchanged(inventory);
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		verifyPersistence(savedOrder, savedItem, reservation);
		return responseMapper.toResponse(savedOrder, savedItem);
	}

	private OrderResponse mapExisting(Order order) {
		OrderItem item = orderItemRepository.findByOrderId(order.getId())
				.orElseThrow(this::conversionConflict);
		return responseMapper.toResponse(order, item);
	}

	private void validateReplayHash(Order order, String requestHash) {
		if (!requestHash.equals(order.getRequestHash())) {
			throw new OrderIdempotencyConflictException(
					"Idempotency key was already used for a different Order request");
		}
	}

	private Offer requireSingleOffer(
			List<Offer> offers,
			OrderConversionTarget target) {
		if (offers.size() != 1
				|| !Objects.equals(offers.getFirst().getId(), target.offerId())) {
			throw conversionConflict();
		}
		return offers.getFirst();
	}

	private Reservation requireSingleReservation(
			List<Reservation> reservations,
			OrderConversionTarget target) {
		if (reservations.size() != 1
				|| !Objects.equals(
						reservations.getFirst().getId(),
						target.reservationId())) {
			throw conversionConflict();
		}
		return reservations.getFirst();
	}

	private void validateLockedState(
			Customer customer,
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer,
			Reservation reservation,
			OrderConversionTarget target,
			Instant transactionTime) {
		validateCustomer(customer);
		boolean mismatch = !sameId(customer.getId(), target.customerId())
				|| !sameId(restaurant.getId(), target.restaurantId())
				|| !sameId(product.getId(), target.productId())
				|| !sameId(inventory.getId(), target.inventoryId())
				|| !sameId(offer.getId(), target.offerId())
				|| !sameId(reservation.getId(), target.reservationId())
				|| reservation.getCustomer() == null
				|| !sameId(reservation.getCustomer().getId(), customer.getId())
				|| reservation.getRestaurant() == null
				|| !sameId(reservation.getRestaurant().getId(), restaurant.getId())
				|| reservation.getOffer() == null
				|| !sameId(reservation.getOffer().getId(), offer.getId())
				|| reservation.getInventory() == null
				|| !sameId(reservation.getInventory().getId(), inventory.getId())
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
		if (mismatch
				|| reservation.getStatus() != ReservationStatus.ACTIVE
				|| reservation.getExpiresAt() == null
				|| !reservation.getExpiresAt().isAfter(transactionTime)) {
			throw conversionConflict();
		}
		validateSnapshots(reservation, offer, product, restaurant);
	}

	private void validateSnapshots(
			Reservation reservation,
			Offer offer,
			Product product,
			Restaurant restaurant) {
		BigDecimal quantity = reservation.getQuantity();
		BigDecimal unitPrice = reservation.getUnitPrice();
		BigDecimal total = reservation.getTotalAmount();
		String currency = reservation.getCurrencyCode();
		if (quantity == null
				|| quantity.compareTo(BigDecimal.ZERO) <= 0
				|| unitPrice == null
				|| unitPrice.compareTo(BigDecimal.ZERO) <= 0
				|| total == null
				|| total.compareTo(BigDecimal.ZERO) <= 0
				|| total.compareTo(
						quantity.multiply(unitPrice)
								.setScale(2, RoundingMode.HALF_UP)) != 0
				|| currency == null
				|| !currency.equals(offer.getCurrencyCode())
				|| !currency.equals(product.getCurrencyCode())
				|| !currency.equals(restaurant.getCurrencyCode())
				|| product.getName() == null
				|| product.getName().isBlank()) {
			throw conversionConflict();
		}
	}

	private void validateCustomer(Customer customer) {
		if (customer.getStatus() != CustomerStatus.ACTIVE) {
			throw conversionConflict();
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
				|| available.compareTo(BigDecimal.ZERO) < 0
				|| reserved.compareTo(BigDecimal.ZERO) < 0
				|| sold.compareTo(BigDecimal.ZERO) < 0
				|| prepared.compareTo(
						available.add(reserved).add(sold)) != 0) {
			throw conversionConflict();
		}
	}

	private void verifyReservationLedger(Inventory inventory) {
		BigDecimal allocated = reservationRepository
				.sumQuantityByInventoryIdAndStatusIn(
						inventory.getId(),
						ALLOCATED_STATUSES);
		if (allocated == null
				|| inventory.getReservedQuantity().compareTo(allocated) != 0) {
			throw conversionConflict();
		}
	}

	private Order saveOrder(Order order) {
		try {
			return orderRepository.saveAndFlush(order);
		} catch (DataIntegrityViolationException exception) {
			if (hasConstraint(exception, IDEMPOTENCY_CONSTRAINT)) {
				throw new OrderIdempotencyRaceException(exception);
			}
			throw exception;
		}
	}

	private OrderItem saveOrderItem(OrderItem item) {
		try {
			return orderItemRepository.saveAndFlush(item);
		} catch (DataIntegrityViolationException exception) {
			if (hasConstraint(exception, RESERVATION_CONSTRAINT)) {
				throw new OrderConversionConflictException(
						"Reservation was already converted to an Order",
						exception);
			}
			throw exception;
		}
	}

	private boolean hasConstraint(Throwable exception, String expected) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException violation) {
				String name = violation.getConstraintName();
				return expected.equals(name)
						|| name != null && name.endsWith("." + expected);
			}
			cause = cause.getCause();
		}
		return false;
	}

	private void verifyPersistence(
			Order order,
			OrderItem item,
			Reservation reservation) {
		if (order.getId() == null
				|| order.getPublicId() == null
				|| item.getId() == null
				|| item.getPublicId() == null
				|| reservation.getStatus() != ReservationStatus.CONVERTED
				|| reservation.getConvertedAt() == null) {
			throw conversionConflict();
		}
	}

	private boolean sameId(Long first, Long second) {
		return first != null && Objects.equals(first, second);
	}

	private OrderConversionConflictException conversionConflict() {
		return new OrderConversionConflictException(
				"Order conversion conflicts with current Reservation state");
	}

	record OrderConversionRequest(
			java.util.UUID customerPublicId,
			java.util.UUID reservationPublicId,
			String idempotencyKey,
			String requestHash) {
	}

	private record InventorySnapshot(
			BigDecimal prepared,
			BigDecimal available,
			BigDecimal reserved,
			BigDecimal sold) {

		static InventorySnapshot of(Inventory inventory) {
			return new InventorySnapshot(
					inventory.getPreparedQuantity(),
					inventory.getAvailableQuantity(),
					inventory.getReservedQuantity(),
					inventory.getSoldQuantity());
		}

		void verifyUnchanged(Inventory inventory) {
			if (prepared.compareTo(inventory.getPreparedQuantity()) != 0
					|| available.compareTo(inventory.getAvailableQuantity()) != 0
					|| reserved.compareTo(inventory.getReservedQuantity()) != 0
					|| sold.compareTo(inventory.getSoldQuantity()) != 0) {
				throw new OrderConversionConflictException(
						"Order conversion conflicts with current Reservation state");
			}
		}
	}
}
