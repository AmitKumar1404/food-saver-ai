package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.foodsaver.entity.Offer;
import com.foodsaver.exception.OfferAlreadyExistsException;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.RestaurantRepository;

import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class OfferServiceImplPersistenceTests {

	@Mock
	private RestaurantRepository restaurantRepository;

	@Mock
	private InventoryRepository inventoryRepository;

	@Mock
	private OfferRepository offerRepository;

	@Mock
	private EntityManager entityManager;

	private OfferServiceImpl offerService;

	@BeforeEach
	void setUp() {
		offerService = new OfferServiceImpl(
				restaurantRepository,
				inventoryRepository,
				offerRepository,
				entityManager);
	}

	@Test
	void translatesEligibilityEvaluationUniqueConstraintViolation() {
		Offer offer = mock(Offer.class);
		DataIntegrityViolationException persistenceException =
				constraintViolation("uk_offers_eligibility_evaluation");
		when(offerRepository.saveAndFlush(offer)).thenThrow(persistenceException);

		OfferAlreadyExistsException translated = assertThrows(
				OfferAlreadyExistsException.class,
				() -> offerService.saveOffer(offer));

		assertSame(persistenceException, translated.getCause());
	}

	@Test
	void doesNotTranslateUnrelatedIntegrityViolation() {
		Offer offer = mock(Offer.class);
		DataIntegrityViolationException persistenceException =
				constraintViolation("uk_offers_public_id");
		when(offerRepository.saveAndFlush(offer)).thenThrow(persistenceException);

		DataIntegrityViolationException propagated = assertThrows(
				DataIntegrityViolationException.class,
				() -> offerService.saveOffer(offer));

		assertSame(persistenceException, propagated);
	}

	private DataIntegrityViolationException constraintViolation(
			String constraintName) {
		ConstraintViolationException constraintViolation =
				mock(ConstraintViolationException.class);
		when(constraintViolation.getConstraintName()).thenReturn(constraintName);
		return new DataIntegrityViolationException(
				"Database constraint violation",
				constraintViolation);
	}
}
