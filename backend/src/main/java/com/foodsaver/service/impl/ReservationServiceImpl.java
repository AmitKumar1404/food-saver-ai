package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.config.ReservationProperties;
import com.foodsaver.dto.request.ReservationCreateRequest;
import com.foodsaver.dto.response.ReservationResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Reservation;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.exception.OfferNotFoundException;
import com.foodsaver.exception.ReservationIdempotencyConflictException;
import com.foodsaver.exception.ReservationValidationException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.service.ReservationService;

@Service
public class ReservationServiceImpl implements ReservationService {

	private static final int MONEY_SCALE = 2;
	private static final int QUANTITY_SCALE = 3;
	private static final int QUANTITY_INTEGER_DIGITS = 9;
	private static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;

	private final CustomerRepository customerRepository;
	private final OfferRepository offerRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationProperties reservationProperties;

	public ReservationServiceImpl(
			CustomerRepository customerRepository,
			OfferRepository offerRepository,
			ReservationRepository reservationRepository,
			ReservationProperties reservationProperties) {
		this.customerRepository = customerRepository;
		this.offerRepository = offerRepository;
		this.reservationRepository = reservationRepository;
		this.reservationProperties = reservationProperties;
	}

	@Override
	@Transactional
	public ReservationResponse createReservation(
			UUID customerPublicId,
			ReservationCreateRequest request,
			String idempotencyKey) {
		if (customerPublicId == null) {
			throw new ReservationValidationException("Customer public ID is required");
		}
		Customer customer = customerRepository.findByPublicId(customerPublicId)
				.orElseThrow(() -> new CustomerNotFoundException(customerPublicId));
		validateCustomer(customer);
		validateIdempotencyKey(idempotencyKey);

		BigDecimal quantity = validateAndNormalizeRequest(request);
		String requestHash = calculateRequestHash(
				customerPublicId,
				request.getOfferPublicId(),
				quantity);

		Reservation existingReservation = reservationRepository
				.findByCustomerIdAndIdempotencyKey(customer.getId(), idempotencyKey)
				.orElse(null);
		if (existingReservation != null) {
			if (requestHash.equals(existingReservation.getRequestHash())) {
				return toResponse(existingReservation);
			}
			throw new ReservationIdempotencyConflictException(
					"Idempotency key was already used for a different Reservation request");
		}

		Offer offer = offerRepository.findByPublicId(request.getOfferPublicId())
				.orElseThrow(() -> new OfferNotFoundException(request.getOfferPublicId()));
		Instant transactionTime = currentTransactionTime();
		validateOfferWindow(offer, transactionTime);

		ReservationSource source = validateReservationSource(offer);
		validateQuantity(quantity, offer.getOfferedQuantity());
		validateCurrency(offer, source.restaurant(), source.product());

		BigDecimal unitPrice = validateUnitPrice(offer.getOfferPrice());
		BigDecimal totalAmount = quantity
				.multiply(unitPrice)
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		Instant reservationExpiry = calculateExpiry(
				transactionTime,
				offer.getExpiresAt());

		Reservation reservation = new Reservation(
				customer,
				source.restaurant(),
				offer,
				source.inventory(),
				quantity,
				unitPrice,
				totalAmount,
				offer.getCurrencyCode(),
				reservationExpiry,
				idempotencyKey,
				requestHash);
		reservation.setStatus(ReservationStatus.ACTIVE);

		return toResponse(reservationRepository.save(reservation));
	}

	Instant currentTransactionTime() {
		return Instant.now();
	}

	private void validateCustomer(Customer customer) {
		if (customer.getStatus() != CustomerStatus.ACTIVE) {
			throw new ReservationValidationException(
					"Customer lifecycle does not permit Reservation creation");
		}
	}

	private void validateIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			throw new ReservationValidationException("Idempotency key is required");
		}
		if (idempotencyKey.length() > IDEMPOTENCY_KEY_MAX_LENGTH) {
			throw new ReservationValidationException(
					"Idempotency key must not exceed 100 characters");
		}
	}

	private BigDecimal validateAndNormalizeRequest(ReservationCreateRequest request) {
		if (request == null) {
			throw new ReservationValidationException("Reservation request is required");
		}
		if (request.getOfferPublicId() == null) {
			throw new ReservationValidationException("Offer public ID is required");
		}

		BigDecimal quantity = request.getQuantity();
		if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new ReservationValidationException(
					"Reservation quantity must be greater than zero");
		}
		if (quantity.scale() > QUANTITY_SCALE
				|| integerDigits(quantity) > QUANTITY_INTEGER_DIGITS) {
			throw new ReservationValidationException(
					"Reservation quantity must have up to 9 integer and 3 fractional digits");
		}
		return quantity.setScale(QUANTITY_SCALE);
	}

	private int integerDigits(BigDecimal value) {
		return Math.max(value.precision() - value.scale(), 0);
	}

	private void validateOfferWindow(Offer offer, Instant transactionTime) {
		if (offer.getStatus() != OfferStatus.ACTIVE) {
			throw new ReservationValidationException(
					"Offer lifecycle does not permit Reservation creation");
		}
		if (offer.getStartAt() == null || offer.getStartAt().isAfter(transactionTime)) {
			throw new ReservationValidationException("Offer has not started");
		}
		if (offer.getExpiresAt() == null
				|| !offer.getExpiresAt().isAfter(transactionTime)) {
			throw new ReservationValidationException("Offer is logically expired");
		}
	}

	private ReservationSource validateReservationSource(Offer offer) {
		Restaurant restaurant = offer.getRestaurant();
		Product product = offer.getProduct();
		Inventory inventory = offer.getInventory();

		boolean relationshipMismatch = restaurant == null
				|| product == null
				|| inventory == null
				|| inventory.getRestaurant() == null
				|| inventory.getProduct() == null
				|| product.getRestaurant() == null
				|| !sameId(restaurant.getId(), inventory.getRestaurant().getId())
				|| !sameId(restaurant.getId(), product.getRestaurant().getId())
				|| !sameId(product.getId(), inventory.getProduct().getId());
		if (relationshipMismatch) {
			throw new ReservationValidationException(
					"Offer relationships are inconsistent");
		}
		if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
			throw new ReservationValidationException(
					"Restaurant lifecycle does not permit Reservation creation");
		}
		if (product.getStatus() != ProductStatus.ACTIVE) {
			throw new ReservationValidationException(
					"Product lifecycle does not permit Reservation creation");
		}
		if (inventory.getStatus() != InventoryStatus.ACTIVE) {
			throw new ReservationValidationException(
					"Inventory lifecycle does not permit Reservation creation");
		}
		return new ReservationSource(restaurant, product, inventory);
	}

	private boolean sameId(Long first, Long second) {
		return first != null && Objects.equals(first, second);
	}

	private void validateQuantity(
			BigDecimal quantity,
			BigDecimal offeredQuantity) {
		if (offeredQuantity == null
				|| offeredQuantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new ReservationValidationException(
					"Offer quantity does not permit Reservation creation");
		}
		if (quantity.compareTo(offeredQuantity) > 0) {
			throw new ReservationValidationException(
					"Reservation quantity exceeds the Offer quantity");
		}
	}

	private void validateCurrency(
			Offer offer,
			Restaurant restaurant,
			Product product) {
		String currencyCode = offer.getCurrencyCode();
		if (currencyCode == null
				|| !currencyCode.equals(restaurant.getCurrencyCode())
				|| !currencyCode.equals(product.getCurrencyCode())) {
			throw new ReservationValidationException(
					"Offer, Product, and Restaurant currencies must match");
		}
	}

	private BigDecimal validateUnitPrice(BigDecimal unitPrice) {
		if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new ReservationValidationException(
					"Offer price does not permit Reservation creation");
		}
		return unitPrice;
	}

	private Instant calculateExpiry(
			Instant transactionTime,
			Instant offerExpiry) {
		Duration reservationTtl = reservationProperties.getReservationTtl();
		if (reservationTtl == null
				|| reservationTtl.isZero()
				|| reservationTtl.isNegative()) {
			throw new ReservationValidationException(
					"Configured Reservation TTL must be greater than zero");
		}

		Instant ttlExpiry = transactionTime.plus(reservationTtl);
		return ttlExpiry.isBefore(offerExpiry) ? ttlExpiry : offerExpiry;
	}

	private String calculateRequestHash(
			UUID customerPublicId,
			UUID offerPublicId,
			BigDecimal normalizedQuantity) {
		String canonicalInput = customerPublicId
				+ "\n"
				+ offerPublicId
				+ "\n"
				+ normalizedQuantity.toPlainString();
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(canonicalInput.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private ReservationResponse toResponse(Reservation reservation) {
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

	private record ReservationSource(
			Restaurant restaurant,
			Product product,
			Inventory inventory) {
	}
}
