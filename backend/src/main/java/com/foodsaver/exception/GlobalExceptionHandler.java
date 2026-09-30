package com.foodsaver.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final String GLOBAL_ERROR_KEY = "_global";
	private static final String TIMEZONE_VALIDATION_ERROR_KEY = "timezoneValid";
	private static final String TIMEZONE_ERROR_KEY = "timezone";

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
			MethodArgumentNotValidException exception,
			HttpServletRequest request) {
		Map<String, List<String>> validationErrors = new LinkedHashMap<>();

		exception.getBindingResult().getFieldErrors().forEach(error ->
				addError(
						validationErrors,
						normalizeFieldName(error.getField()),
						resolveMessage(error)));
		exception.getBindingResult().getGlobalErrors().forEach(error ->
				addError(validationErrors, GLOBAL_ERROR_KEY, resolveMessage(error)));

		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				"Validation failed",
				request.getRequestURI(),
				validationErrors);

		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
			MethodArgumentTypeMismatchException exception,
			HttpServletRequest request) {
		String parameterName = exception.getName() != null
				? exception.getName()
				: GLOBAL_ERROR_KEY;
		String expectedType = exception.getRequiredType() != null
				? exception.getRequiredType().getSimpleName()
				: "value";
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				"Invalid path parameter",
				request.getRequestURI(),
				Map.of(
						parameterName,
						List.of("Must be a valid " + expectedType)));

		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
			HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				"Malformed request body",
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(InvalidIdempotencyKeyException.class)
	public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKeyException(
			InvalidIdempotencyKeyException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(OfferValidationException.class)
	public ResponseEntity<ErrorResponse> handleOfferValidationException(
			OfferValidationException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.badRequest().body(response);
	}

	@ExceptionHandler(RestaurantNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleRestaurantNotFoundException(
			RestaurantNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(ProductNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleProductNotFoundException(
			ProductNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(InventoryNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleInventoryNotFoundException(
			InventoryNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(SurplusDetectionNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleSurplusDetectionNotFoundException(
			SurplusDetectionNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(InventoryAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleInventoryAlreadyExistsException(
			InventoryAlreadyExistsException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@ExceptionHandler(CustomerAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleCustomerAlreadyExistsException(
			CustomerAlreadyExistsException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@ExceptionHandler(CustomerNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleCustomerNotFoundException(
			CustomerNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(OfferNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleOfferNotFoundException(
			OfferNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(FoodEligibilityEvaluationNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleFoodEligibilityEvaluationNotFoundException(
			FoodEligibilityEvaluationNotFoundException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler({
			OrderNotFoundException.class,
			ReservationNotFoundException.class
	})
	public ResponseEntity<ErrorResponse> handleOrderingNotFound(
			RuntimeException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.NOT_FOUND.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
	}

	@ExceptionHandler(OfferEligibilityException.class)
	public ResponseEntity<ErrorResponse> handleOfferEligibilityException(
			OfferEligibilityException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@ExceptionHandler(OfferAlreadyExistsException.class)
	public ResponseEntity<ErrorResponse> handleOfferAlreadyExistsException(
			OfferAlreadyExistsException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@ExceptionHandler({
			ReservationValidationException.class,
			ReservationIdempotencyConflictException.class,
			ReservationAllocationConflictException.class,
			OrderIdempotencyConflictException.class,
			OrderConversionConflictException.class,
			OrderCompletionConflictException.class
	})
	public ResponseEntity<ErrorResponse> handleReservationConflict(
			RuntimeException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.CONFLICT.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}

	@ExceptionHandler(OrderValidationException.class)
	public ResponseEntity<ErrorResponse> handleOrderValidation(
			OrderValidationException exception,
			HttpServletRequest request) {
		ErrorResponse response = new ErrorResponse(
				Instant.now(),
				HttpStatus.BAD_REQUEST.value(),
				exception.getMessage(),
				request.getRequestURI(),
				Map.of());

		return ResponseEntity.badRequest().body(response);
	}

	private void addError(
			Map<String, List<String>> errors,
			String key,
			String message) {
		errors.computeIfAbsent(key, ignored -> new ArrayList<>()).add(message);
	}

	private String resolveMessage(ObjectError error) {
		return error.getDefaultMessage() != null
				? error.getDefaultMessage()
				: "Invalid value";
	}

	private String normalizeFieldName(String fieldName) {
		return TIMEZONE_VALIDATION_ERROR_KEY.equals(fieldName)
				? TIMEZONE_ERROR_KEY
				: fieldName;
	}
}
