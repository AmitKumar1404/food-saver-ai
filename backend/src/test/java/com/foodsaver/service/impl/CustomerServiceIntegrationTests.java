package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.exception.CustomerAlreadyExistsException;
import com.foodsaver.service.CustomerService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerServiceIntegrationTests {

	@Autowired
	private CustomerService customerService;

	@Test
	void createsActiveCustomerWithCanonicalEmailAndSystemFields() {
		String email = "Service." + UUID.randomUUID() + "@Example.COM";
		CustomerCreateRequest request =
				request(email, "Service Customer", "+14155552671");

		CustomerResponse response = customerService.createCustomer(request);

		assertEquals(email.toLowerCase(Locale.ROOT), response.getEmail());
		assertEquals("Service Customer", response.getDisplayName());
		assertEquals("+14155552671", response.getContactPhone());
		assertEquals(CustomerStatus.ACTIVE, response.getStatus());
		assertNotNull(response.getPublicId());
		assertNotNull(response.getCreatedAt());
		assertNotNull(response.getUpdatedAt());
		assertFalse(Arrays.stream(CustomerResponse.class.getDeclaredFields())
				.anyMatch(field ->
						"id".equals(field.getName())
								|| "version".equals(field.getName())));
	}

	@Test
	void rejectsDuplicateCanonicalEmail() {
		String email = "duplicate." + UUID.randomUUID() + "@example.com";
		customerService.createCustomer(
				request(email, "First Customer", null));

		assertThrows(
				CustomerAlreadyExistsException.class,
				() -> customerService.createCustomer(
						request(email, "Second Customer", null)));
	}

	@Test
	void rejectsMixedCaseDuplicateEmail() {
		String email = "mixed." + UUID.randomUUID() + "@example.com";
		customerService.createCustomer(
				request(email, "First Customer", null));

		assertThrows(
				CustomerAlreadyExistsException.class,
				() -> customerService.createCustomer(
						request(
								email.toUpperCase(Locale.ROOT),
								"Second Customer",
								null)));
	}

	@Test
	void supportsMissingContactPhone() {
		CustomerCreateRequest request = request(
				"optional." + UUID.randomUUID() + "@example.com",
				"Optional Phone Customer",
				null);

		CustomerResponse response = customerService.createCustomer(request);

		assertNull(response.getContactPhone());
	}

	@Test
	void retrievesCustomerByPublicId() {
		CustomerResponse created = customerService.createCustomer(request(
				"lookup." + UUID.randomUUID() + "@example.com",
				"Lookup Customer",
				"+14155552671"));

		CustomerResponse retrieved = customerService.getCustomer(created.getPublicId());

		assertEquals(created.getPublicId(), retrieved.getPublicId());
		assertEquals(created.getEmail(), retrieved.getEmail());
		assertEquals(created.getDisplayName(), retrieved.getDisplayName());
		assertEquals(created.getContactPhone(), retrieved.getContactPhone());
		assertEquals(CustomerStatus.ACTIVE, retrieved.getStatus());
		assertEquals(created.getCreatedAt(), retrieved.getCreatedAt());
		assertEquals(created.getUpdatedAt(), retrieved.getUpdatedAt());
	}

	private CustomerCreateRequest request(
			String email,
			String displayName,
			String contactPhone) {
		CustomerCreateRequest request = new CustomerCreateRequest();
		request.setEmail(email);
		request.setDisplayName(displayName);
		request.setContactPhone(contactPhone);
		return request;
	}
}
