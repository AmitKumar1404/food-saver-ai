package com.foodsaver.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.entity.Customer;
import com.foodsaver.enums.CustomerStatus;

import jakarta.persistence.EntityManager;

@SpringBootTest
@ActiveProfiles("test")
class CustomerPersistenceIntegrationTests {

	@Autowired
	private CustomerRepository customerRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@Transactional
	void persistsCanonicalEmailAndSystemManagedFields() {
		String mixedCaseEmail = "Customer." + UUID.randomUUID() + "@Example.COM";
		Customer customer = customer(mixedCaseEmail, "Persistence Customer");

		customer = customerRepository.saveAndFlush(customer);
		UUID publicId = customer.getPublicId();
		entityManager.clear();

		Customer persisted = customerRepository.findByPublicId(publicId).orElseThrow();
		assertEquals(
				mixedCaseEmail.toLowerCase(Locale.ROOT),
				persisted.getEmail());
		assertEquals(CustomerStatus.ACTIVE, persisted.getStatus());
		assertNull(persisted.getContactPhone());
		assertNotNull(persisted.getVersion());
		assertNotNull(persisted.getCreatedAt());
		assertNotNull(persisted.getUpdatedAt());
		assertTrue(customerRepository.existsByEmail(
				Customer.canonicalizeEmail(mixedCaseEmail)));
		assertEquals(
				publicId,
				customerRepository
						.findByEmail(Customer.canonicalizeEmail(mixedCaseEmail))
						.orElseThrow()
						.getPublicId());
	}

	@Test
	@Transactional
	void rejectsEmailThatDuplicatesAnotherCanonicalEmail() {
		String mixedCaseEmail = "Duplicate." + UUID.randomUUID() + "@Example.COM";
		customerRepository.saveAndFlush(
				customer(mixedCaseEmail, "First Customer"));

		Customer duplicate = customer(
				mixedCaseEmail.toUpperCase(Locale.ROOT),
				"Second Customer");

		assertThrows(
				DataIntegrityViolationException.class,
				() -> customerRepository.saveAndFlush(duplicate));
	}

	@Test
	void canonicalizationUsesLocaleIndependentLowercaseOnly() {
		assertEquals(
				" customer@example.com ",
				Customer.canonicalizeEmail(" Customer@Example.COM "));
		assertNull(Customer.canonicalizeEmail(null));
	}

	private Customer customer(String email, String displayName) {
		Customer customer = new Customer();
		customer.setEmail(email);
		customer.setDisplayName(displayName);
		return customer;
	}
}
