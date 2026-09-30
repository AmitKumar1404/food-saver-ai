package com.foodsaver.service;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;

public interface CustomerService {

	CustomerResponse createCustomer(CustomerCreateRequest request);
}
