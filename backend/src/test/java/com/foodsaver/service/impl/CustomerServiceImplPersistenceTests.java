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

import com.foodsaver.entity.Customer;
import com.foodsaver.exception.CustomerAlreadyExistsException;
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
