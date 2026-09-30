package com.foodsaver.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.foodsaver.dto.request.CustomerCreateRequest;
import com.foodsaver.dto.response.CustomerResponse;
import com.foodsaver.enums.CustomerStatus;
import com.foodsaver.exception.CustomerAlreadyExistsException;
import com.foodsaver.exception.ErrorResponse;
import com.foodsaver.exception.GlobalExceptionHandler;
import com.foodsaver.service.CustomerService;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@ExtendWith(MockitoExtension.class)
class CustomerControllerTests {

	private static final String ENDPOINT = "/api/v1/customers";
	private static final UUID CUSTOMER_PUBLIC_ID = UUID.randomUUID();
	private static final Instant CREATED_AT = Instant.parse("2026-09-30T06:30:00Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-09-30T06:30:00Z");

	@Mock
	private CustomerService customerService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
				.standaloneSetup(new CustomerController(customerService))
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void createsCustomerAndDelegatesToService() throws Exception {
		when(customerService.createCustomer(any(CustomerCreateRequest.class)))
				.thenReturn(response("+14155552671"));

		mockMvc.perform(request(validJson()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId").value(CUSTOMER_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.email").value("customer.name@example.com"))
				.andExpect(jsonPath("$.displayName").value("Customer Name"))
				.andExpect(jsonPath("$.contactPhone").value("+14155552671"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist());

		ArgumentCaptor<CustomerCreateRequest> requestCaptor =
				ArgumentCaptor.forClass(CustomerCreateRequest.class);
		verify(customerService).createCustomer(requestCaptor.capture());
		CustomerCreateRequest delegatedRequest = requestCaptor.getValue();
		assertEquals("Customer.Name@Example.COM", delegatedRequest.getEmail());
		assertEquals("Customer Name", delegatedRequest.getDisplayName());
		assertEquals("+14155552671", delegatedRequest.getContactPhone());
	}

	@Test
	void returnsBadRequestWhenEmailIsMissing() throws Exception {
		assertValidationError(
				"""
				{
				  "displayName": "Customer Name"
				}
				""",
				"email",
				"Customer email is required");
	}

	@Test
	void returnsBadRequestWhenEmailIsBlank() throws Exception {
		assertValidationError(
				json("   ", "Customer Name", "+14155552671"),
				"email",
				"Customer email is required");
	}

	@Test
	void returnsBadRequestWhenEmailIsInvalid() throws Exception {
		assertValidationError(
				json("not-an-email", "Customer Name", "+14155552671"),
				"email",
				"Customer email must be a valid email address");
	}

	@Test
	void returnsBadRequestWhenEmailExceeds254Characters() throws Exception {
		assertValidationError(
				json(
						"a".repeat(243) + "@example.com",
						"Customer Name",
						"+14155552671"),
				"email",
				"Customer email must not exceed 254 characters");
	}

	@Test
	void returnsBadRequestWhenDisplayNameIsMissing() throws Exception {
		assertValidationError(
				"""
				{
				  "email": "customer@example.com"
				}
				""",
				"displayName",
				"Display name is required");
	}

	@Test
	void returnsBadRequestWhenDisplayNameIsBlank() throws Exception {
		assertValidationError(
				json("customer@example.com", "   ", "+14155552671"),
				"displayName",
				"Display name is required");
	}

	@Test
	void returnsBadRequestWhenDisplayNameExceeds100Characters() throws Exception {
		assertValidationError(
				json(
						"customer@example.com",
						"a".repeat(101),
						"+14155552671"),
				"displayName",
				"Display name must not exceed 100 characters");
	}

	@Test
	void createsCustomerWithoutOptionalContactPhone() throws Exception {
		when(customerService.createCustomer(any(CustomerCreateRequest.class)))
				.thenReturn(response(null));
		String requestBody = """
				{
				  "email": "customer@example.com",
				  "displayName": "Customer Name"
				}
				""";

		mockMvc.perform(request(requestBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.contactPhone").doesNotExist());

		ArgumentCaptor<CustomerCreateRequest> requestCaptor =
				ArgumentCaptor.forClass(CustomerCreateRequest.class);
		verify(customerService).createCustomer(requestCaptor.capture());
		assertNull(requestCaptor.getValue().getContactPhone());
	}

	@Test
	void returnsBadRequestWhenContactPhoneExceeds32Characters() throws Exception {
		assertValidationError(
				json("customer@example.com", "Customer Name", "1".repeat(33)),
				"contactPhone",
				"Contact phone must not exceed 32 characters");
	}

	@Test
	void returnsConflictWhenCanonicalEmailAlreadyExists() throws Exception {
		String message = "A Customer already exists for the supplied email";
		when(customerService.createCustomer(any(CustomerCreateRequest.class)))
				.thenThrow(new CustomerAlreadyExistsException(message));

		mockMvc.perform(request(validJson()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());
	}

	@Test
	void returnsErrorResponseForMalformedJson() throws Exception {
		String requestBody = """
				{
				  "email": "customer@example.com",
				  "displayName": "Customer Name"
				""";

		mockMvc.perform(request(requestBody))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Malformed request body"))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors").isEmpty())
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(customerService);
	}

	@Test
	void ignoresClientSuppliedServerControlledFields() throws Exception {
		when(customerService.createCustomer(any(CustomerCreateRequest.class)))
				.thenReturn(response("+14155552671"));
		String requestBody = """
				{
				  "email": "Customer.Name@Example.COM",
				  "displayName": "Customer Name",
				  "contactPhone": "+14155552671",
				  "publicId": "00000000-0000-0000-0000-000000000000",
				  "id": 99,
				  "version": 42,
				  "status": "CLOSED",
				  "createdAt": "2000-01-01T00:00:00Z",
				  "updatedAt": "2000-01-01T00:00:00Z"
				}
				""";

		mockMvc.perform(request(requestBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.publicId").value(CUSTOMER_PUBLIC_ID.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
				.andExpect(jsonPath("$.id").doesNotExist())
				.andExpect(jsonPath("$.version").doesNotExist());

		ArgumentCaptor<CustomerCreateRequest> requestCaptor =
				ArgumentCaptor.forClass(CustomerCreateRequest.class);
		verify(customerService).createCustomer(requestCaptor.capture());
		assertEquals("Customer.Name@Example.COM", requestCaptor.getValue().getEmail());
	}

	@Test
	void documentsCustomerCreateResponses() throws Exception {
		ApiResponses responses = CustomerController.class
				.getMethod("createCustomer", CustomerCreateRequest.class)
				.getAnnotation(ApiResponses.class);

		assertResponseSchema(responses, "201", CustomerResponse.class);
		assertResponseSchema(responses, "400", ErrorResponse.class);
		assertResponseSchema(responses, "409", ErrorResponse.class);
	}

	private void assertValidationError(
			String body,
			String field,
			String message) throws Exception {
		mockMvc.perform(request(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Validation failed"))
				.andExpect(jsonPath("$.path").value(ENDPOINT))
				.andExpect(jsonPath("$.errors." + field).value(hasItem(message)))
				.andExpect(jsonPath("$.error").doesNotExist());

		verifyNoInteractions(customerService);
	}

	private MockHttpServletRequestBuilder request(String body) {
		return post(ENDPOINT)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private String validJson() {
		return json(
				"Customer.Name@Example.COM",
				"Customer Name",
				"+14155552671");
	}

	private String json(String email, String displayName, String contactPhone) {
		return """
				{
				  "email": "%s",
				  "displayName": "%s",
				  "contactPhone": "%s"
				}
				""".formatted(email, displayName, contactPhone);
	}

	private CustomerResponse response(String contactPhone) {
		CustomerResponse response = new CustomerResponse();
		response.setPublicId(CUSTOMER_PUBLIC_ID);
		response.setEmail("customer.name@example.com");
		response.setDisplayName("Customer Name");
		response.setContactPhone(contactPhone);
		response.setStatus(CustomerStatus.ACTIVE);
		response.setCreatedAt(CREATED_AT);
		response.setUpdatedAt(UPDATED_AT);
		return response;
	}

	private void assertResponseSchema(
			ApiResponses responses,
			String responseCode,
			Class<?> expectedSchema) {
		ApiResponse response = Arrays.stream(responses.value())
				.filter(candidate -> responseCode.equals(candidate.responseCode()))
				.findFirst()
				.orElseThrow();
		assertEquals(
				expectedSchema,
				response.content()[0].schema().implementation());
	}
}
