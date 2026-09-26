package com.foodsaver.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ErrorResponse(
		Instant timestamp,
		int status,
		String message,
		String path,
		Map<String, List<String>> errors) {
}
