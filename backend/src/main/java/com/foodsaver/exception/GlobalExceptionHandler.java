package com.foodsaver.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
