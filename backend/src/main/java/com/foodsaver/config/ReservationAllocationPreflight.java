package com.foodsaver.config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.foodsaver.entity.Inventory;
import com.foodsaver.entity.OrderingReconciliationMarker;
import com.foodsaver.enums.OrderingReconciliationState;
import com.foodsaver.enums.ReservationStatus;
import com.foodsaver.repository.InventoryRepository;
import com.foodsaver.repository.OrderingReconciliationMarkerRepository;
import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.projection.InventoryReservationLedgerTotal;
import com.foodsaver.service.ReservationLedgerService;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class ReservationAllocationPreflight implements ApplicationRunner {

	private static final Logger LOGGER =
			LoggerFactory.getLogger(ReservationAllocationPreflight.class);
	private static final Set<ReservationStatus> ALLOCATED_STATUSES =
			Set.of(ReservationStatus.ACTIVE, ReservationStatus.CONVERTED);

	private final ReservationProperties properties;
	private final ReservationAllocationActivation activation;
	private final OrderingReconciliationMarkerRepository markerRepository;
	private final InventoryRepository inventoryRepository;
	private final ReservationRepository reservationRepository;
	private final ReservationLedgerService reservationLedgerService;
	private final ReservationAllocationPreflightObserver preflightObserver;

	public ReservationAllocationPreflight(
			ReservationProperties properties,
			ReservationAllocationActivation activation,
			OrderingReconciliationMarkerRepository markerRepository,
			InventoryRepository inventoryRepository,
			ReservationRepository reservationRepository,
			ReservationLedgerService reservationLedgerService,
			ReservationAllocationPreflightObserver preflightObserver) {
		this.properties = properties;
		this.activation = activation;
		this.markerRepository = markerRepository;
		this.inventoryRepository = inventoryRepository;
		this.reservationRepository = reservationRepository;
		this.reservationLedgerService = reservationLedgerService;
		this.preflightObserver = preflightObserver;
	}

	@Override
	@Transactional(isolation = Isolation.REPEATABLE_READ)
	public void run(ApplicationArguments arguments) {
		if (!properties.isAllocationEnabled()) {
			LOGGER.info("Reservation allocation is disabled");
			return;
		}

		OrderingReconciliationMarker marker = markerRepository
				.findByReleaseIdentifierForActivation(
						ReservationAllocationRelease.IDENTIFIER)
				.orElseThrow(this::preflightFailure);
		boolean firstActivation =
				marker.getState() == OrderingReconciliationState.RECONCILED_BASELINE;
		if (!firstActivation
				&& marker.getState() != OrderingReconciliationState.ACTIVATED) {
			throw preflightFailure();
		}

		verifyInventoryAndLedgerState(firstActivation);
		if (firstActivation) {
			marker.activate(Instant.now());
			markerRepository.saveAndFlush(marker);
		}
		activateAfterCommit();
		LOGGER.info(
				"Reservation allocation preflight succeeded for release {}",
				ReservationAllocationRelease.IDENTIFIER);
	}

	private void verifyInventoryAndLedgerState(boolean requireReconciledBaseline) {
		if (requireReconciledBaseline
				&& reservationRepository.countByStatusIn(ALLOCATED_STATUSES) != 0) {
			throw preflightFailure();
		}
		if (reservationLedgerService.hasOrphanConvertedReservations()) {
			throw preflightFailure();
		}
		var inventories =
				inventoryRepository.findAllByOrderByIdForReconciliation();
		preflightObserver.afterInventorySnapshotRead();
		Map<Long, BigDecimal> allocatedByInventory = reservationLedgerService
				.outstandingByInventory()
				.stream()
				.collect(Collectors.toMap(
						InventoryReservationLedgerTotal::inventoryId,
						InventoryReservationLedgerTotal::allocatedQuantity,
						(first, second) -> {
							throw preflightFailure();
						}));
		for (Inventory inventory : inventories) {
			if (!hasValidInventoryEquation(inventory)
					|| !hasValidReservationLedger(
							inventory,
							allocatedByInventory)
					|| requireReconciledBaseline
							&& inventory.getReservedQuantity()
									.compareTo(BigDecimal.ZERO) != 0) {
				throw preflightFailure();
			}
		}
	}

	private void activateAfterCommit() {
		TransactionSynchronizationManager.registerSynchronization(
				new TransactionSynchronization() {
					@Override
					public void afterCommit() {
						activation.activate();
					}
				});
	}

	private IllegalStateException preflightFailure() {
		LOGGER.error(
				"Reservation allocation preflight failed for release {}: "
						+ "reconciliation is required",
				ReservationAllocationRelease.IDENTIFIER);
		return new IllegalStateException(
				"Reservation allocation preflight failed; reconciliation is required");
	}

	private boolean hasValidInventoryEquation(Inventory inventory) {
		BigDecimal prepared = inventory.getPreparedQuantity();
		BigDecimal available = inventory.getAvailableQuantity();
		BigDecimal reserved = inventory.getReservedQuantity();
		BigDecimal sold = inventory.getSoldQuantity();
		return prepared != null
				&& available != null
				&& reserved != null
				&& sold != null
				&& available.compareTo(BigDecimal.ZERO) >= 0
				&& reserved.compareTo(BigDecimal.ZERO) >= 0
				&& sold.compareTo(BigDecimal.ZERO) >= 0
				&& prepared.compareTo(available.add(reserved).add(sold)) == 0;
	}

	private boolean hasValidReservationLedger(
			Inventory inventory,
			Map<Long, BigDecimal> allocatedByInventory) {
		BigDecimal allocated = allocatedByInventory.getOrDefault(
				inventory.getId(),
				BigDecimal.ZERO);
		return allocated != null
				&& inventory.getReservedQuantity().compareTo(allocated) == 0;
	}
}
