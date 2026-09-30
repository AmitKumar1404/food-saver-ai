package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.config.ReservationProperties;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.OfferNotFoundException;
import com.foodsaver.exception.ReservationAllocationConflictException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.exception.ReservationIdempotencyRaceException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.repository.projection.OfferAllocationTarget;
import com.foodsaver.repository.projection.ReservationAllocationTarget;
import com.foodsaver.service.ReservationLedgerService;

@Service
class ReservationAllocationCommand {

	private static final Logger LOGGER =
			LoggerFactory.getLogger(ReservationAllocationCommand.class);
	private static final int MONEY_SCALE = 2;
	private static final String IDEMPOTENCY_CONSTRAINT =
			"uk_reservations_customer_idempotency";
	private static final Set<ReservationStatus> ALLOCATED_STATUSES =
			Set.of(ReservationStatus.ACTIVE, ReservationStatus.CONVERTED);

	private final CustomerRepository customerRepository;
	private final RestaurantRepository restaurantRepository;
	private final ProductRepository productRepository;
	private final InventoryRepository inventoryRepository;
	private final OfferRepository offerRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationLedgerService reservationLedgerService;
	private final ReservationProperties reservationProperties;
	private final ReservationResponseMapper responseMapper;
	private final ReservationAllocationTransactionObserver transactionObserver;

	ReservationAllocationCommand(
			CustomerRepository customerRepository,
			RestaurantRepository restaurantRepository,
			ProductRepository productRepository,
			InventoryRepository inventoryRepository,
			OfferRepository offerRepository,
			ReservationRepository reservationRepository,
			ReservationLedgerService reservationLedgerService,
			ReservationProperties reservationProperties,
			ReservationResponseMapper responseMapper,
			ReservationAllocationTransactionObserver transactionObserver) {
		this.customerRepository = customerRepository;
		this.restaurantRepository = restaurantRepository;
		this.productRepository = productRepository;
		this.inventoryRepository = inventoryRepository;
		this.offerRepository = offerRepository;
		this.reservationRepository = reservationRepository;
		this.reservationLedgerService = reservationLedgerService;
		this.reservationProperties = reservationProperties;
		this.responseMapper = responseMapper;
		this.transactionObserver = transactionObserver;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	ReservationResponse allocate(ReservationAllocationRequest request) {
		Customer customer = customerRepository
				.findByPublicIdForAllocation(request.customerPublicId())
				.orElseThrow(() -> new CustomerNotFoundException(
						request.customerPublicId()));
		validateCustomer(customer);

		Reservation existing = reservationRepository
				.findByCustomerIdAndIdempotencyKey(
						customer.getId(),
						request.idempotencyKey())
				.orElse(null);
		Long replayReservationId = null;
		if (existing != null) {
			validateReplayHash(existing, request.requestHash());
			if (existing.getStatus() != ReservationStatus.ACTIVE) {
				return responseMapper.toResponse(existing);
			}
			replayReservationId = existing.getId();
		}

		OfferAllocationTarget selectedTarget = offerRepository
				.findAllocationTargetByPublicId(request.offerPublicId())
				.orElseThrow(() -> new OfferNotFoundException(
						request.offerPublicId()));

		Restaurant restaurant = restaurantRepository
				.findByIdForAllocation(selectedTarget.restaurantId())
				.orElseThrow(this::allocationStateConflict);
		Product product = productRepository
				.findByIdAndRestaurantIdForAllocation(
						selectedTarget.productId(),
						selectedTarget.restaurantId())
				.orElseThrow(this::allocationStateConflict);
		transactionObserver.beforeInventoryLock(request.idempotencyKey());
		Inventory inventory = inventoryRepository
				.findByIdAndRestaurantId(
						selectedTarget.inventoryId(),
						selectedTarget.restaurantId())
				.orElseThrow(this::allocationStateConflict);
		transactionObserver.afterInventoryLock(request.idempotencyKey());

		List<ReservationAllocationTarget> reservationTargets =
				reservationRepository.findAllocationTargetsByInventoryIdAndStatus(
						inventory.getId(),
						ReservationStatus.ACTIVE);
		List<Long> offerIds = offerIds(selectedTarget, reservationTargets);
		List<Offer> lockedOffers =
				offerRepository.findAllByIdInOrderByIdForAllocation(offerIds);
		transactionObserver.afterOfferLocks(
				request.idempotencyKey(),
				lockedOffers.stream().map(Offer::getId).toList());
		List<Long> reservationIds = reservationTargets.stream()
				.map(ReservationAllocationTarget::reservationId)
				.sorted()
				.toList();
		List<Reservation> lockedReservations = reservationIds.isEmpty()
				? List.of()
				: reservationRepository.findAllByIdInOrderByIdForAllocation(
						reservationIds);
		transactionObserver.afterReservationLocks(
				request.idempotencyKey(),
				lockedReservations.stream().map(Reservation::getId).toList());

		Instant transactionTime = Reservation.normalizeTimestamp(
				currentTransactionTime());
		if (transactionTime == null) {
			throw allocationStateConflict();
		}
		Offer selectedOffer = validateLockedState(
				selectedTarget,
				reservationTargets,
				customer,
				restaurant,
				product,
				inventory,
				lockedOffers,
				lockedReservations);

		Instant reservationExpiry = null;
		if (replayReservationId == null) {
			validateLifecycle(
					restaurant,
					product,
					inventory,
					selectedOffer,
					transactionTime);
			reservationExpiry = calculateExpiry(
					transactionTime,
					selectedOffer.getExpiresAt());
		}

		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		releaseExpiredReservations(
				inventory,
				lockedReservations,
				transactionTime);
		reservationRepository.flush();
		transactionObserver.afterExpiryFlush(request.idempotencyKey());
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);

		if (replayReservationId != null) {
			Long expectedReplayId = replayReservationId;
			Reservation replayed = lockedReservations.stream()
					.filter(reservation -> Objects.equals(
							reservation.getId(),
							expectedReplayId))
					.findFirst()
					.orElseThrow(this::allocationStateConflict);
			return responseMapper.toResponse(replayed);
		}

		validateAllocationCapacity(
				selectedOffer,
				inventory,
				request.quantity());
		validateCurrency(selectedOffer, restaurant, product);

		BigDecimal unitPrice = validateUnitPrice(selectedOffer.getOfferPrice());
		BigDecimal totalAmount = request.quantity()
				.multiply(unitPrice)
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		allocateInventory(inventory, request.quantity());
		Reservation reservation = new Reservation(
				customer,
				restaurant,
				selectedOffer,
				inventory,
				request.quantity(),
				unitPrice,
				totalAmount,
				selectedOffer.getCurrencyCode(),
				reservationExpiry,
				request.idempotencyKey(),
				request.requestHash());
		reservation.setStatus(ReservationStatus.ACTIVE);
		reservation.initializeCreationTimestamp(transactionTime);

		Reservation saved = saveReservation(reservation);
		transactionObserver.afterReservationPersistence(request.idempotencyKey());
		verifyInventoryEquation(inventory);
		verifyReservationLedger(inventory);
		return responseMapper.toResponse(saved);
	}

	Instant currentTransactionTime() {
		return Instant.now();
	}

	private void validateReplayHash(
			Reservation existing,
			String requestHash) {
		if (!requestHash.equals(existing.getRequestHash())) {
			throw new ReservationIdempotencyConflictException(
					"Idempotency key was already used for a different "
							+ "Reservation request");
		}
	}

	private List<Long> offerIds(
			OfferAllocationTarget selectedTarget,
			List<ReservationAllocationTarget> reservationTargets) {
		Collection<Long> ids = new LinkedHashSet<>();
		ids.add(selectedTarget.offerId());
		reservationTargets.stream()
				.map(ReservationAllocationTarget::offerId)
				.forEach(ids::add);
		return ids.stream().sorted().toList();
	}

	private Offer validateLockedState(
			OfferAllocationTarget selectedTarget,
			List<ReservationAllocationTarget> reservationTargets,
			Customer customer,
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			List<Offer> lockedOffers,
			List<Reservation> lockedReservations) {
		validateCustomer(customer);
		List<Long> expectedOfferIds = offerIds(selectedTarget, reservationTargets);
		List<Long> lockedOfferIds = lockedOffers.stream()
				.map(Offer::getId)
				.toList();
		if (!expectedOfferIds.equals(lockedOfferIds)) {
			throw allocationStateConflict();
		}
		if (lockedOffers.stream().anyMatch(offer ->
				offer.getRestaurant() == null
						|| !sameId(offer.getRestaurant().getId(), restaurant.getId())
						|| offer.getProduct() == null
						|| !sameId(offer.getProduct().getId(), product.getId())
						|| offer.getInventory() == null
						|| !sameId(offer.getInventory().getId(), inventory.getId()))) {
			throw allocationStateConflict();
		}
		if (lockedReservations.size() != reservationTargets.size()) {
			throw allocationStateConflict();
		}

		Offer selectedOffer = lockedOffers.stream()
				.filter(offer -> Objects.equals(
						offer.getId(),
						selectedTarget.offerId()))
				.findFirst()
				.orElseThrow(this::allocationStateConflict);
		validateRelationships(
				selectedTarget,
				restaurant,
				product,
				inventory,
				selectedOffer);
		validateReservationTargets(
				reservationTargets,
				lockedReservations,
				inventory);
		return selectedOffer;
	}

	private void validateRelationships(
			OfferAllocationTarget target,
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer) {
		boolean mismatch = !sameId(restaurant.getId(), target.restaurantId())
				|| !sameId(product.getId(), target.productId())
				|| !sameId(inventory.getId(), target.inventoryId())
				|| inventory.getRestaurant() == null
				|| !sameId(inventory.getRestaurant().getId(), restaurant.getId())
				|| inventory.getProduct() == null
				|| !sameId(inventory.getProduct().getId(), product.getId())
				|| product.getRestaurant() == null
				|| !sameId(product.getRestaurant().getId(), restaurant.getId())
				|| offer.getRestaurant() == null
				|| !sameId(offer.getRestaurant().getId(), restaurant.getId())
				|| offer.getProduct() == null
				|| !sameId(offer.getProduct().getId(), product.getId())
				|| offer.getInventory() == null
				|| !sameId(offer.getInventory().getId(), inventory.getId())
				|| offer.getEligibilityEvaluation() == null
				|| offer.getEligibilityEvaluation().getStatus()
						!= FoodEligibilityStatus.ELIGIBLE_FOR_OFFER;
		if (mismatch) {
			throw allocationStateConflict();
		}
	}

	private void validateReservationTargets(
			List<ReservationAllocationTarget> targets,
			List<Reservation> reservations,
			Inventory inventory) {
		List<Long> targetIds = targets.stream()
				.map(ReservationAllocationTarget::reservationId)
				.sorted()
				.toList();
		List<Long> lockedIds = reservations.stream()
				.map(Reservation::getId)
				.toList();
		if (!targetIds.equals(lockedIds)
				|| reservations.stream().anyMatch(reservation ->
						reservation.getStatus() != ReservationStatus.ACTIVE)) {
			throw allocationStateConflict();
		}
		for (ReservationAllocationTarget target : targets) {
			Reservation reservation = reservations.stream()
					.filter(candidate -> Objects.equals(
							candidate.getId(),
							target.reservationId()))
					.findFirst()
					.orElseThrow(this::allocationStateConflict);
			boolean mismatch = reservation.getOffer() == null
					|| !sameId(reservation.getOffer().getId(), target.offerId())
					|| reservation.getInventory() == null
					|| !sameId(
							reservation.getInventory().getId(),
							inventory.getId())
					|| reservation.getExpiresAt() == null
					|| !reservation.getExpiresAt().equals(target.expiresAt())
					|| reservation.getQuantity() == null
					|| target.quantity() == null
					|| reservation.getQuantity().compareTo(target.quantity()) != 0;
			if (mismatch) {
				throw allocationStateConflict();
			}
		}
	}

	private void validateCustomer(Customer customer) {
		if (customer.getStatus() != CustomerStatus.ACTIVE) {
			throw allocationStateConflict();
		}
	}

	private void validateLifecycle(
			Restaurant restaurant,
			Product product,
			Inventory inventory,
			Offer offer,
			Instant transactionTime) {
		boolean invalid = restaurant.getStatus() != RestaurantStatus.ACTIVE
				|| product.getStatus() != ProductStatus.ACTIVE
				|| inventory.getStatus() != InventoryStatus.ACTIVE
				|| offer.getStatus() != OfferStatus.ACTIVE
				|| offer.getStartAt() == null
				|| offer.getStartAt().isAfter(transactionTime)
				|| offer.getExpiresAt() == null
				|| !offer.getExpiresAt().isAfter(transactionTime);
		if (invalid) {
			throw allocationStateConflict();
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
			LOGGER.warn("Reservation allocation rejected: Inventory equation mismatch");
			throw allocationStateConflict();
		}
	}

	private void verifyReservationLedger(Inventory inventory) {
		if (reservationLedgerService.hasOrphanConvertedReservations(
				inventory.getId())) {
			throw allocationStateConflict();
		}
		BigDecimal allocated =
				reservationLedgerService.outstandingQuantity(inventory.getId());
		if (allocated == null
				|| inventory.getReservedQuantity().compareTo(allocated) != 0) {
			LOGGER.warn("Reservation allocation rejected: Reservation ledger mismatch");
			throw allocationStateConflict();
		}
	}

	private void releaseExpiredReservations(
			Inventory inventory,
			List<Reservation> reservations,
			Instant transactionTime) {
		for (Reservation reservation : reservations) {
			if (reservation.getStatus() == ReservationStatus.ACTIVE
					&& reservation.getExpiresAt() != null
					&& !reservation.getExpiresAt().isAfter(transactionTime)) {
				BigDecimal newReserved = inventory.getReservedQuantity()
						.subtract(reservation.getQuantity());
				if (newReserved.compareTo(BigDecimal.ZERO) < 0) {
					throw allocationStateConflict();
				}
				inventory.setAvailableQuantity(
						inventory.getAvailableQuantity().add(
								reservation.getQuantity()));
				inventory.setReservedQuantity(newReserved);
				reservation.setStatus(ReservationStatus.EXPIRED);
				reservation.setExpiredAt(transactionTime);
			}
		}
	}

	private void validateAllocationCapacity(
			Offer offer,
			Inventory inventory,
			BigDecimal requestedQuantity) {
		BigDecimal allocated = reservationRepository
				.sumQuantityByOfferIdAndStatusIn(
						offer.getId(),
						ALLOCATED_STATUSES);
		BigDecimal offered = offer.getOfferedQuantity();
		if (allocated == null
				|| offered == null
				|| allocated.compareTo(BigDecimal.ZERO) < 0
				|| offered.compareTo(BigDecimal.ZERO) <= 0
				|| allocated.compareTo(offered) > 0) {
			throw allocationStateConflict();
		}
		BigDecimal remaining = offered.subtract(allocated);
		if (requestedQuantity.compareTo(BigDecimal.ZERO) <= 0
				|| requestedQuantity.compareTo(remaining) > 0
				|| requestedQuantity.compareTo(
						inventory.getAvailableQuantity()) > 0) {
			throw allocationStateConflict();
		}
	}

	private void validateCurrency(
			Offer offer,
			Restaurant restaurant,
			Product product) {
		String currency = offer.getCurrencyCode();
		if (currency == null
				|| !currency.equals(restaurant.getCurrencyCode())
				|| !currency.equals(product.getCurrencyCode())) {
			throw allocationStateConflict();
		}
	}

	private BigDecimal validateUnitPrice(BigDecimal unitPrice) {
		if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw allocationStateConflict();
		}
		return unitPrice;
	}

	private Instant calculateExpiry(Instant transactionTime, Instant offerExpiry) {
		Duration ttl = reservationProperties.getReservationTtl();
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw allocationStateConflict();
		}
		Instant ttlExpiry = transactionTime.plus(ttl);
		Instant expiry = Reservation.normalizeTimestamp(
				ttlExpiry.isBefore(offerExpiry) ? ttlExpiry : offerExpiry);
		if (!expiry.isAfter(transactionTime)) {
			throw allocationStateConflict();
		}
		return expiry;
	}

	private void allocateInventory(
			Inventory inventory,
			BigDecimal requestedQuantity) {
		BigDecimal available = inventory.getAvailableQuantity()
				.subtract(requestedQuantity);
		BigDecimal reserved = inventory.getReservedQuantity()
				.add(requestedQuantity);
		if (available.compareTo(BigDecimal.ZERO) < 0
				|| reserved.compareTo(BigDecimal.ZERO) < 0) {
			throw allocationStateConflict();
		}
		inventory.setAvailableQuantity(available);
		inventory.setReservedQuantity(reserved);
	}

	private Reservation saveReservation(Reservation reservation) {
		try {
			return reservationRepository.saveAndFlush(reservation);
		} catch (DataIntegrityViolationException exception) {
			if (isIdempotencyConstraint(exception)) {
				throw new ReservationIdempotencyRaceException(exception);
			}
			throw exception;
		}
	}

	private boolean isIdempotencyConstraint(Throwable exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException violation) {
				String constraintName = violation.getConstraintName();
				return IDEMPOTENCY_CONSTRAINT.equals(constraintName)
						|| constraintName != null
								&& constraintName.endsWith(
										"." + IDEMPOTENCY_CONSTRAINT);
			}
			cause = cause.getCause();
		}
		return false;
	}

	private boolean sameId(Long first, Long second) {
		return first != null && Objects.equals(first, second);
	}

	private ReservationAllocationConflictException allocationStateConflict() {
		return new ReservationAllocationConflictException(
				"Reservation allocation conflicts with current marketplace state");
	}

	record ReservationAllocationRequest(
			UUID customerPublicId,
			UUID offerPublicId,
			BigDecimal quantity,
			String idempotencyKey,
			String requestHash) {
	}
}
