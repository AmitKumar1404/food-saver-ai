package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.config.ReservationAllocationPreflight;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OfferRepository;
import com.foodsaver.repository.ProductRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.RestaurantRepository;

import jakarta.persistence.LockModeType;

class ReservationAllocationLockContractTests {

	@Test
	void allocationCommandUsesReadCommittedIsolation() throws Exception {
		Transactional transactional = ReservationAllocationCommand.class
				.getDeclaredMethod(
						"allocate",
						Class.forName(
								"com.foodsaver.service.impl."
										+ "ReservationAllocationCommand"
										+ "$ReservationAllocationRequest"))
				.getAnnotation(Transactional.class);

		assertEquals(Isolation.READ_COMMITTED, transactional.isolation());
	}

	@Test
	void orderConversionCommandUsesReadCommittedIsolation() throws Exception {
		Transactional transactional = OrderConversionCommand.class
				.getDeclaredMethod(
						"convert",
						Class.forName(
								"com.foodsaver.service.impl."
										+ "OrderConversionCommand"
										+ "$OrderConversionRequest"))
				.getAnnotation(Transactional.class);

		assertEquals(Isolation.READ_COMMITTED, transactional.isolation());
	}

	@Test
	void repositoriesDeclareFinalizedAllocationLockModes() throws Exception {
		assertLock(
				CustomerRepository.class,
				"findByPublicIdForAllocation",
				LockModeType.PESSIMISTIC_WRITE,
				UUID.class);
		assertLock(
				RestaurantRepository.class,
				"findByIdForAllocation",
				LockModeType.PESSIMISTIC_READ,
				Long.class);
		assertLock(
				ProductRepository.class,
				"findByIdAndRestaurantIdForAllocation",
				LockModeType.PESSIMISTIC_READ,
				Long.class,
				Long.class);
		assertLock(
				InventoryRepository.class,
				"findByIdAndRestaurantId",
				LockModeType.PESSIMISTIC_WRITE,
				Long.class,
				Long.class);
		assertLock(
				OfferRepository.class,
				"findAllByIdInOrderByIdForAllocation",
				LockModeType.PESSIMISTIC_WRITE,
				Collection.class);
		assertLock(
				ReservationRepository.class,
				"findAllByIdInOrderByIdForAllocation",
				LockModeType.PESSIMISTIC_WRITE,
				Collection.class);
	}

	@Test
	void preflightUsesRepeatableReadWithoutInventoryLocks() throws Exception {
		Transactional transactional = ReservationAllocationPreflight.class
				.getMethod(
						"run",
						org.springframework.boot.ApplicationArguments.class)
				.getAnnotation(Transactional.class);
		Lock lock = InventoryRepository.class
				.getMethod("findAllByOrderByIdForReconciliation")
				.getAnnotation(Lock.class);

		assertEquals(Isolation.REPEATABLE_READ, transactional.isolation());
		assertEquals(null, lock);
	}

	@Test
	void activeTargetLookupIsNotMistakenForTheReservationLock() throws Exception {
		Lock lock = ReservationRepository.class
				.getMethod(
						"findAllocationTargetsByInventoryIdAndStatus",
						Long.class,
						ReservationStatus.class)
				.getAnnotation(Lock.class);

		assertEquals(null, lock);
	}

	private void assertLock(
			Class<?> repository,
			String method,
			LockModeType expected,
			Class<?>... parameters) throws Exception {
		Lock lock = repository.getMethod(method, parameters).getAnnotation(Lock.class);
		assertEquals(expected, lock.value());
	}
}
