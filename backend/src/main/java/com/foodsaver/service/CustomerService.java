package com.foodsaver.service;

import java.util.UUID;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;

public interface CustomerService {

	CustomerResponse createCustomer(CustomerCreateRequest request);

	CustomerResponse getCustomer(UUID customerPublicId);
}
