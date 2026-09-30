package com.foodsaver.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.foodsaver.entity.Customer;
import com.foodsaver.exception.CustomerAlreadyExistsException;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.repository.CustomerRepository;

@ExtendWith(MockitoExtension.class)
class CustomerServiceImplPersistenceTests {

	@Mock
	private CustomerRepository customerRepository;

	private CustomerServiceImpl customerService;

	@BeforeEach
	void setUp() {
		customerService = new CustomerServiceImpl(customerRepository);
	}

	@Test
	void translatesEmailUniqueConstraintViolation() {
		Customer customer = mock(Customer.class);
		DataIntegrityViolationException persistenceException =
				constraintViolation("uk_customers_email");
		when(customerRepository.saveAndFlush(customer)).thenThrow(persistenceException);

		CustomerAlreadyExistsException translated = assertThrows(
				CustomerAlreadyExistsException.class,
				() -> customerService.saveCustomer(customer));

		assertSame(persistenceException, translated.getCause());
	}

	@Test
	void doesNotTranslateUnrelatedIntegrityViolation() {
		Customer customer = mock(Customer.class);
		DataIntegrityViolationException persistenceException =
				constraintViolation("uk_customers_public_id");
		when(customerRepository.saveAndFlush(customer)).thenThrow(persistenceException);

		DataIntegrityViolationException propagated = assertThrows(
				DataIntegrityViolationException.class,
				() -> customerService.saveCustomer(customer));

		assertSame(persistenceException, propagated);
	}

	@Test
	void throwsNotFoundWhenPublicIdDoesNotExist() {
		UUID customerPublicId = UUID.randomUUID();
		when(customerRepository.findByPublicId(customerPublicId))
				.thenReturn(Optional.empty());

		assertThrows(
				CustomerNotFoundException.class,
				() -> customerService.getCustomer(customerPublicId));

		verify(customerRepository).findByPublicId(customerPublicId);
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
