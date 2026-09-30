package com.foodsaver.service.impl;

import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;
import com.foodsaver.entity.Customer;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.exception.CustomerAlreadyExistsException;
import com.foodsaver.exception.CustomerNotFoundException;
import com.foodsaver.repository.CustomerRepository;
import com.foodsaver.service.CustomerService;

@Service
public class CustomerServiceImpl implements CustomerService {

	private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_customers_email";
	private static final String DUPLICATE_EMAIL_MESSAGE =
			"A Customer already exists for the supplied email";

	private final CustomerRepository customerRepository;

	public CustomerServiceImpl(CustomerRepository customerRepository) {
		this.customerRepository = customerRepository;
	}

	@Override
	@Transactional
	public CustomerResponse createCustomer(CustomerCreateRequest request) {
		String canonicalEmail = Customer.canonicalizeEmail(request.getEmail());
		if (customerRepository.existsByEmail(canonicalEmail)) {
			throw new CustomerAlreadyExistsException(DUPLICATE_EMAIL_MESSAGE);
		}

		Customer customer = new Customer();
		customer.setEmail(canonicalEmail);
		customer.setDisplayName(request.getDisplayName());
		customer.setContactPhone(request.getContactPhone());
		customer.setStatus(CustomerStatus.ACTIVE);

		return toResponse(saveCustomer(customer));
	}

	@Override
	@Transactional(readOnly = true)
	public CustomerResponse getCustomer(UUID customerPublicId) {
		return customerRepository.findByPublicId(customerPublicId)
				.map(this::toResponse)
				.orElseThrow(() -> new CustomerNotFoundException(customerPublicId));
	}

	Customer saveCustomer(Customer customer) {
		try {
			return customerRepository.saveAndFlush(customer);
		} catch (DataIntegrityViolationException exception) {
			if (isEmailUniqueConstraint(exception)) {
				throw new CustomerAlreadyExistsException(
						DUPLICATE_EMAIL_MESSAGE,
						exception);
			}
			throw exception;
		}
	}

	private boolean isEmailUniqueConstraint(Throwable exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintViolation) {
				String constraintName = constraintViolation.getConstraintName();
				return EMAIL_UNIQUE_CONSTRAINT.equals(constraintName)
						|| constraintName != null
								&& constraintName.endsWith("." + EMAIL_UNIQUE_CONSTRAINT);
			}
			cause = cause.getCause();
		}
		return false;
	}

	private CustomerResponse toResponse(Customer customer) {
		CustomerResponse response = new CustomerResponse();
		response.setPublicId(customer.getPublicId());
		response.setEmail(customer.getEmail());
		response.setDisplayName(customer.getDisplayName());
		response.setContactPhone(customer.getContactPhone());
		response.setStatus(customer.getStatus());
		response.setCreatedAt(customer.getCreatedAt());
		response.setUpdatedAt(customer.getUpdatedAt());
		return response;
	}
}
