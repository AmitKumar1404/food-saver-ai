package com.foodsaver.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
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

@ExtendWith(MockitoExtension.class)
class ReservationAllocationPreflightTests {

	private static final Set<ReservationStatus> ALLOCATED =
			Set.of(ReservationStatus.ACTIVE, ReservationStatus.CONVERTED);

	@Mock
	private OrderingReconciliationMarkerRepository markerRepository;
	@Mock
	private InventoryRepository inventoryRepository;
	@Mock
	private ReservationRepository reservationRepository;
	@Mock
	private ReservationLedgerService reservationLedgerService;
	@Mock
	private ReservationAllocationPreflightObserver preflightObserver;
	@Mock
	private ApplicationArguments arguments;

	private ReservationProperties properties;
	private ReservationAllocationActivation activation;
	private ReservationAllocationPreflight preflight;

	@BeforeEach
	void setUp() {
		properties = new ReservationProperties();
		activation = new ReservationAllocationActivation();
		preflight = new ReservationAllocationPreflight(
				properties,
				activation,
				markerRepository,
				inventoryRepository,
				reservationRepository,
				reservationLedgerService,
				preflightObserver);
	}

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void disabledAllocationRemainsInactiveWithoutInspectingData() {
		runPreflight();

		assertFalse(activation.isActive());
		verify(markerRepository, never())
				.findByReleaseIdentifierForActivation(any());
		verify(inventoryRepository, never())
				.findAllByOrderByIdForReconciliation();
	}

	@Test
	void enabledAllocationFailsClosedWhenMarkerIsMissing() {
		enableAllocation();
		when(markerRepository.findByReleaseIdentifierForActivation(
				ReservationAllocationRelease.IDENTIFIER))
				.thenReturn(Optional.empty());

		assertThrows(IllegalStateException.class, this::runPreflight);
		assertFalse(activation.isActive());
	}

	@Test
	void enabledAllocationRejectsWrongReleaseMarker() {
		enableAllocation();
		when(markerRepository.findByReleaseIdentifierForActivation(
				ReservationAllocationRelease.IDENTIFIER))
				.thenReturn(Optional.empty());

		assertThrows(IllegalStateException.class, this::runPreflight);
		assertFalse(activation.isActive());
	}

	@Test
	void validMarkerAndCleanBaselineActivateOnlyAfterCommit() {
		enableAllocation();
		OrderingReconciliationMarker marker = marker();
		arrangeMarker(marker);
		when(reservationRepository.countByStatusIn(ALLOCATED)).thenReturn(0L);
		when(inventoryRepository.findAllByOrderByIdForReconciliation())
				.thenReturn(List.of());
		when(reservationLedgerService.outstandingByInventory())
				.thenReturn(List.of());

		runPreflightWithoutCommit();

		assertFalse(activation.isActive());
		assertTrue(TransactionSynchronizationManager.isSynchronizationActive());
		TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);

		assertTrue(activation.isActive());
		assertTrue(marker.getState() == OrderingReconciliationState.ACTIVATED);
		verify(markerRepository).saveAndFlush(marker);
	}

	@Test
	void activatedReleaseAcceptsCurrentBalancedReservationLedger() {
		enableAllocation();
		OrderingReconciliationMarker marker = marker();
		marker.activate(Instant.parse("2026-09-30T08:01:00Z"));
		arrangeMarker(marker);
		Inventory inventory = inventory("5.000", "4.000", "1.000", "0.000");
		when(inventoryRepository.findAllByOrderByIdForReconciliation())
				.thenReturn(List.of(inventory));
		when(reservationLedgerService.outstandingByInventory())
				.thenReturn(List.of(new InventoryReservationLedgerTotal(
						inventory.getId(),
						new BigDecimal("1.000"))));

		runPreflight();

		assertTrue(activation.isActive());
		verify(reservationRepository, never()).countByStatusIn(any());
		verify(markerRepository, never()).saveAndFlush(any());
	}

	@Test
	void activatedReleaseRejectsOrphanConvertedReservation() {
		enableAllocation();
		OrderingReconciliationMarker marker = marker();
		marker.activate(Instant.parse("2026-09-30T08:01:00Z"));
		arrangeMarker(marker);
		when(reservationLedgerService.hasOrphanConvertedReservations())
				.thenReturn(true);

		assertThrows(IllegalStateException.class, this::runPreflight);
		assertFalse(activation.isActive());
		verify(inventoryRepository, never())
				.findAllByOrderByIdForReconciliation();
	}

	@Test
	void balancedLegacyQuantitiesWithoutMarkerRemainRejected() {
		enableAllocation();
		when(markerRepository.findByReleaseIdentifierForActivation(
				ReservationAllocationRelease.IDENTIFIER))
				.thenReturn(Optional.empty());

		assertThrows(IllegalStateException.class, this::runPreflight);
		verify(inventoryRepository, never())
				.findAllByOrderByIdForReconciliation();
		assertFalse(activation.isActive());
	}

	@Test
	void firstActivationRejectsNonZeroReservedQuantity() {
		enableAllocation();
		arrangeMarker(marker());
		when(reservationRepository.countByStatusIn(ALLOCATED)).thenReturn(0L);
		Inventory inventory = inventory("5.000", "4.000", "1.000", "0.000");
		when(inventoryRepository.findAllByOrderByIdForReconciliation())
				.thenReturn(List.of(inventory));
		when(reservationLedgerService.outstandingByInventory())
				.thenReturn(List.of(new InventoryReservationLedgerTotal(
						inventory.getId(),
						new BigDecimal("1.000"))));

		assertThrows(IllegalStateException.class, this::runPreflight);
		assertFalse(activation.isActive());
	}

	@Test
	void firstActivationRejectsLegacyActiveReservation() {
		assertLegacyStatusRejected(ReservationStatus.ACTIVE);
	}

	@Test
	void firstActivationRejectsLegacyConvertedReservation() {
		assertLegacyStatusRejected(ReservationStatus.CONVERTED);
	}

	private void assertLegacyStatusRejected(ReservationStatus status) {
		enableAllocation();
		arrangeMarker(marker());
		when(reservationRepository.countByStatusIn(ALLOCATED)).thenReturn(1L);

		assertThrows(IllegalStateException.class, this::runPreflight);
		assertFalse(activation.isActive());
		verify(inventoryRepository, never())
				.findAllByOrderByIdForReconciliation();
	}

	private void enableAllocation() {
		properties.setAllocationEnabled(true);
	}

	private OrderingReconciliationMarker marker() {
		return new OrderingReconciliationMarker(
				ReservationAllocationRelease.IDENTIFIER,
				Instant.parse("2026-09-30T08:00:00Z"));
	}

	private void arrangeMarker(OrderingReconciliationMarker marker) {
		when(markerRepository.findByReleaseIdentifierForActivation(
				ReservationAllocationRelease.IDENTIFIER))
				.thenReturn(Optional.of(marker));
	}

	private Inventory inventory(
			String prepared,
			String available,
			String reserved,
			String sold) {
		Inventory inventory = new Inventory();
		ReflectionTestUtils.setField(inventory, "id", 30L);
		inventory.setPreparedQuantity(new BigDecimal(prepared));
		inventory.setAvailableQuantity(new BigDecimal(available));
		inventory.setReservedQuantity(new BigDecimal(reserved));
		inventory.setSoldQuantity(new BigDecimal(sold));
		return inventory;
	}

	private void runPreflight() {
		runPreflightWithoutCommit();
		TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
	}

	private void runPreflightWithoutCommit() {
		TransactionSynchronizationManager.initSynchronization();
		preflight.run(arguments);
	}
}
