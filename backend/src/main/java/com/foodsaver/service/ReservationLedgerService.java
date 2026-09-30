package com.foodsaver.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.foodsaver.repository.ReservationRepository;
import com.foodsaver.repository.projection.InventoryReservationLedgerTotal;

@Service
public class ReservationLedgerService {

	private final ReservationRepository reservationRepository;

	public ReservationLedgerService(
			ReservationRepository reservationRepository) {
		this.reservationRepository = reservationRepository;
	}

	public BigDecimal outstandingQuantity(Long inventoryId) {
		return reservationRepository
				.sumOutstandingQuantityByInventoryId(inventoryId);
	}

	public List<InventoryReservationLedgerTotal> outstandingByInventory() {
		return reservationRepository.sumOutstandingQuantityByInventory();
	}

	public boolean hasOrphanConvertedReservations() {
		return reservationRepository.countOrphanConvertedReservations() != 0;
	}

	public boolean hasOrphanConvertedReservations(Long inventoryId) {
		return reservationRepository
				.countOrphanConvertedReservationsByInventoryId(inventoryId) != 0;
	}
}
