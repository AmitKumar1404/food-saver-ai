package com.foodsaver.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.OfferCreateRequest;
import com.foodsaver.dto.response.OfferResponse;
import com.foodsaver.entity.FoodEligibilityEvaluation;
import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.Offer;
import com.foodsaver.entity.Product;
import com.foodsaver.entity.Restaurant;
import com.foodsaver.entity.SurplusDetection;
import com.foodsaver.enums.FoodEligibilityStatus;
import com.foodsaver.enums.InventoryStatus;
import com.foodsaver.enums.OfferStatus;
import com.foodsaver.enums.ProductStatus;
import com.foodsaver.enums.RestaurantStatus;
import com.foodsaver.exception.FoodEligibilityEvaluationNotFoundException;
import com.foodsaver.exception.OfferAlreadyExistsException;
import com.foodsaver.exception.OfferEligibilityException;
import com.foodsaver.exception.OfferValidationException;
import com.foodsaver.exception.RestaurantNotFoundException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.RestaurantRepository;
import com.foodsaver.service.OfferService;

import jakarta.persistence.EntityManager;

@Service
public class OfferServiceImpl implements OfferService {

	private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
	private static final int MONEY_SCALE = 2;
	private static final String ELIGIBILITY_EVALUATION_UNIQUE_CONSTRAINT =
			"uk_offers_eligibility_evaluation";

	private final RestaurantRepository restaurantRepository;
	private final InventoryRepository inventoryRepository;
	private final OfferRepository offerRepository;
	private final EntityManager entityManager;

	public OfferServiceImpl(
			RestaurantRepository restaurantRepository,
			InventoryRepository inventoryRepository,
			OfferRepository offerRepository,
			EntityManager entityManager) {
		this.restaurantRepository = restaurantRepository;
		this.inventoryRepository = inventoryRepository;
		this.offerRepository = offerRepository;
		this.entityManager = entityManager;
	}

	@Override
	@Transactional
	public OfferResponse createOffer(
			UUID restaurantPublicId,
			OfferCreateRequest request) {
		Restaurant restaurant = restaurantRepository.findByPublicId(restaurantPublicId)
				.orElseThrow(() -> new RestaurantNotFoundException(restaurantPublicId));
		OwnedEvaluation selectedEvaluation = findOwnedEvaluation(
				request.getEligibilityEvaluationPublicId(),
				restaurant);
		FoodEligibilityEvaluation evaluation = selectedEvaluation.evaluation();

		validateEligibilityStatus(evaluation);

		Inventory inventory = inventoryRepository
				.findByIdAndRestaurantId(
						selectedEvaluation.inventoryId(),
						restaurant.getId())
				.orElseThrow(() -> evaluationNotFound(
						request.getEligibilityEvaluationPublicId(),
						restaurantPublicId));
		Product product = inventory.getProduct();

		validateRelationshipChain(
				restaurant,
				evaluation,
				inventory,
				product,
				selectedEvaluation.productId(),
				request.getEligibilityEvaluationPublicId());
		validateLifecycle(restaurant, product, inventory);
		validateStaleness(evaluation, inventory);
		validateQuantity(request.getOfferedQuantity(), evaluation, inventory);
		validateCurrency(restaurant, product);

		Instant transactionTime = Instant.now();
		validateExpiration(request.getExpiresAt(), transactionTime);
		validateNoConflictingOffer(evaluation, inventory, transactionTime);

		BigDecimal originalPrice = product.getBasePrice();
		BigDecimal offerPrice = calculateOfferPrice(
				originalPrice,
				request.getDiscountPercentage());

		Offer offer = new Offer(
				restaurant,
				product,
				inventory,
				evaluation,
				originalPrice,
				request.getDiscountPercentage(),
				offerPrice,
				product.getCurrencyCode(),
				request.getOfferedQuantity(),
				transactionTime,
				request.getExpiresAt());
		offer.setStatus(OfferStatus.ACTIVE);

		return toResponse(saveOffer(offer));
	}

	Offer saveOffer(Offer offer) {
		try {
			return offerRepository.saveAndFlush(offer);
		} catch (DataIntegrityViolationException exception) {
			if (isEligibilityEvaluationUniqueConstraint(exception)) {
				throw new OfferAlreadyExistsException(
						"An Offer already exists for the selected eligibility evaluation",
						exception);
			}
			throw exception;
		}
	}

	private boolean isEligibilityEvaluationUniqueConstraint(Throwable exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintViolation) {
				String constraintName = constraintViolation.getConstraintName();
				return ELIGIBILITY_EVALUATION_UNIQUE_CONSTRAINT.equals(constraintName)
						|| constraintName != null
								&& constraintName.endsWith(
										"." + ELIGIBILITY_EVALUATION_UNIQUE_CONSTRAINT);
			}
			cause = cause.getCause();
		}
		return false;
	}

	private OwnedEvaluation findOwnedEvaluation(
			UUID evaluationPublicId,
			Restaurant restaurant) {
		return entityManager.createQuery(
						"""
						select evaluation, inventory.id, product.id
						from FoodEligibilityEvaluation evaluation
						join evaluation.surplusDetection detection
						join detection.inventory inventory
						join inventory.product product
						where evaluation.publicId = :evaluationPublicId
						  and inventory.restaurant.id = :restaurantId
						  and product.restaurant.id = :restaurantId
						""",
						Object[].class)
				.setParameter("evaluationPublicId", evaluationPublicId)
				.setParameter("restaurantId", restaurant.getId())
				.getResultStream()
				.map(result -> new OwnedEvaluation(
						(FoodEligibilityEvaluation) result[0],
						(Long) result[1],
						(Long) result[2]))
				.findFirst()
				.orElseThrow(() -> evaluationNotFound(
						evaluationPublicId,
						restaurant.getPublicId()));
	}

	private void validateEligibilityStatus(FoodEligibilityEvaluation evaluation) {
		if (evaluation.getStatus() != FoodEligibilityStatus.ELIGIBLE_FOR_OFFER) {
			throw new OfferEligibilityException(
					"Food eligibility evaluation does not permit Offer creation");
		}
	}

	private void validateRelationshipChain(
			Restaurant restaurant,
			FoodEligibilityEvaluation evaluation,
			Inventory inventory,
			Product product,
			Long expectedProductId,
			UUID evaluationPublicId) {
		SurplusDetection detection = evaluation.getSurplusDetection();
		boolean relationshipMismatch = detection == null
				|| detection.getInventory() == null
				|| !Objects.equals(detection.getInventory().getId(), inventory.getId())
				|| product == null
				|| !Objects.equals(product.getId(), expectedProductId)
				|| inventory.getRestaurant() == null
				|| !Objects.equals(inventory.getRestaurant().getId(), restaurant.getId())
				|| product.getRestaurant() == null
				|| !Objects.equals(product.getRestaurant().getId(), restaurant.getId());

		if (relationshipMismatch) {
			throw evaluationNotFound(evaluationPublicId, restaurant.getPublicId());
		}
	}

	private void validateLifecycle(
			Restaurant restaurant,
			Product product,
			Inventory inventory) {
		if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
			throw new OfferEligibilityException(
					"Restaurant lifecycle does not permit Offer creation");
		}
		if (product.getStatus() != ProductStatus.ACTIVE) {
			throw new OfferEligibilityException(
					"Product lifecycle does not permit Offer creation");
		}
		if (inventory.getStatus() != InventoryStatus.ACTIVE) {
			throw new OfferEligibilityException(
					"Inventory lifecycle does not permit Offer creation");
		}
	}

	private void validateStaleness(
			FoodEligibilityEvaluation evaluation,
			Inventory inventory) {
		boolean versionChanged = !Objects.equals(
				inventory.getVersion(),
				evaluation.getEvaluatedInventoryVersion());
		boolean quantityChanged = inventory.getAvailableQuantity() == null
				|| evaluation.getEvaluatedAvailableQuantity() == null
				|| inventory.getAvailableQuantity().compareTo(
						evaluation.getEvaluatedAvailableQuantity()) != 0;

		if (versionChanged || quantityChanged) {
			throw new OfferEligibilityException(
					"Food eligibility evaluation is stale for the current Inventory");
		}
	}

	private void validateQuantity(
			BigDecimal offeredQuantity,
			FoodEligibilityEvaluation evaluation,
			Inventory inventory) {
		if (offeredQuantity == null || offeredQuantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new OfferEligibilityException(
					"Offered quantity must be greater than zero");
		}
		if (offeredQuantity.compareTo(inventory.getAvailableQuantity()) > 0
				|| offeredQuantity.compareTo(
						evaluation.getEvaluatedAvailableQuantity()) > 0) {
			throw new OfferEligibilityException(
					"Offered quantity exceeds eligible Inventory availability");
		}
	}

	private void validateCurrency(Restaurant restaurant, Product product) {
		if (!Objects.equals(product.getCurrencyCode(), restaurant.getCurrencyCode())) {
			throw new OfferEligibilityException(
					"Product and Restaurant currencies must match");
		}
	}

	private void validateExpiration(Instant expiresAt, Instant startAt) {
		if (expiresAt == null || !expiresAt.isAfter(startAt)) {
			throw new OfferValidationException(
					"Offer expiry must be after its marketplace start time");
		}
	}

	private void validateNoConflictingOffer(
			FoodEligibilityEvaluation evaluation,
			Inventory inventory,
			Instant transactionTime) {
		if (offerRepository.findFirstByEligibilityEvaluationId(
				evaluation.getId()).isPresent()) {
			throw new OfferAlreadyExistsException(
					"An Offer already exists for the selected eligibility evaluation");
		}
		if (offerRepository
				.findFirstByInventoryIdAndStatusAndExpiresAtAfterOrderByIdAsc(
				inventory.getId(),
				OfferStatus.ACTIVE,
				transactionTime)
				.isPresent()) {
			throw new OfferAlreadyExistsException(
					"An open Offer already exists for the selected Inventory");
		}
	}

	private BigDecimal calculateOfferPrice(
			BigDecimal originalPrice,
			BigDecimal discountPercentage) {
		if (originalPrice == null || originalPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new OfferEligibilityException(
					"Product price does not permit Offer creation");
		}
		if (discountPercentage == null) {
			throw new OfferEligibilityException(
					"Discount percentage is required");
		}

		BigDecimal discountAmount = originalPrice
				.multiply(discountPercentage)
				.divide(ONE_HUNDRED);
		BigDecimal offerPrice = originalPrice
				.subtract(discountAmount)
				.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

		if (offerPrice.compareTo(BigDecimal.ZERO) <= 0
				|| offerPrice.compareTo(originalPrice) >= 0) {
			throw new OfferEligibilityException(
					"Discount must produce a positive Offer price below the original price");
		}
		return offerPrice;
	}

	private OfferResponse toResponse(Offer offer) {
		OfferResponse response = new OfferResponse();
		response.setPublicId(offer.getPublicId());
		response.setRestaurantPublicId(offer.getRestaurant().getPublicId());
		response.setProductPublicId(offer.getProduct().getPublicId());
		response.setInventoryPublicId(offer.getInventory().getPublicId());
		response.setEligibilityEvaluationPublicId(
				offer.getEligibilityEvaluation().getPublicId());
		response.setOriginalPrice(offer.getOriginalPrice());
		response.setDiscountPercentage(offer.getDiscountPercentage());
		response.setOfferPrice(offer.getOfferPrice());
		response.setCurrencyCode(offer.getCurrencyCode());
		response.setOfferedQuantity(offer.getOfferedQuantity());
		response.setStartAt(offer.getStartAt());
		response.setExpiresAt(offer.getExpiresAt());
		response.setStatus(offer.getStatus());
		response.setCreatedAt(offer.getCreatedAt());
		response.setUpdatedAt(offer.getUpdatedAt());
		return response;
	}

	private FoodEligibilityEvaluationNotFoundException evaluationNotFound(
			UUID evaluationPublicId,
			UUID restaurantPublicId) {
		return new FoodEligibilityEvaluationNotFoundException(
				"Food eligibility evaluation not found: " + evaluationPublicId
						+ " for restaurant: " + restaurantPublicId);
	}

	private record OwnedEvaluation(
			FoodEligibilityEvaluation evaluation,
			Long inventoryId,
			Long productId) {
	}
}
