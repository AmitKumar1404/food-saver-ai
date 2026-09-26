# Global Exception Handling

## 1. Module Overview

The Global Exception Handling module provides a consistent application-level error response for FoodSaver AI REST APIs.

Without centralized handling, framework-generated responses may vary by exception type and Spring configuration. Controllers may also accumulate repetitive `try/catch` blocks that mix HTTP error policy with successful request handling. This module establishes one reusable error shape and maps understood exceptions in one place.

Implemented files:

- `backend/src/main/java/com/foodsaver/exception/ErrorResponse.java`
- `backend/src/main/java/com/foodsaver/exception/GlobalExceptionHandler.java`

Existing exception integrated by this module:

- `backend/src/main/java/com/foodsaver/exception/RestaurantNotFoundException.java`

## 2. Scope

Currently implemented:

- Jakarta Bean Validation failures represented by `MethodArgumentNotValidException` → `400 Bad Request`.
- Missing restaurants represented by `RestaurantNotFoundException` → `404 Not Found`.
- Field-level and object-level validation details.
- A consistent JSON response structure for both exception categories.

Not implemented in this step:

- Malformed JSON handling.
- Invalid UUID/path-variable conversion handling.
- Conflict handling, including uniqueness or optimistic-lock failures.
- Generic `500 Internal Server Error` handling.
- Security, Kafka, Redis, AI, RAG, or food-safety behavior.
- Logging, correlation IDs, localization, or alerting.

## 3. Problem with the Default Error Response

Before this module, validation failures used Spring Boot's default error response. Depending only on framework defaults creates several problems:

- The response contract can change with framework configuration or upgrades.
- Validation details may not be grouped in a client-friendly format.
- Different exception types may return different response structures.
- API clients must understand framework-specific fields rather than application-owned fields.
- Adding local `try/catch` logic to every controller would duplicate status and response construction.

FoodSaver AI now owns a small, stable error contract for the exception types it explicitly understands.

## 4. `ErrorResponse` Design

`ErrorResponse` is an immutable Java record:

| Field | Java type | Purpose |
| --- | --- | --- |
| `timestamp` | `Instant` | UTC point when the response was created |
| `status` | `int` | Numeric HTTP status |
| `message` | `String` | Safe summary for the API client |
| `path` | `String` | Request URI that failed |
| `errors` | `Map<String, List<String>>` | Detailed messages grouped by field or global key |

Design decisions:

- `Instant` provides an unambiguous UTC timestamp.
- A numeric status matches the HTTP response status without exposing framework types.
- `path` helps clients and operators identify the failing endpoint.
- `Map<String, List<String>>` preserves multiple violations for the same field.
- Business errors without field details return an empty map rather than `null`.
- The response excludes stack traces, exception class names, SQL details, filesystem paths, and sensitive request information.

## 5. `GlobalExceptionHandler` Design

`GlobalExceptionHandler` uses `@RestControllerAdvice`.

`@RestControllerAdvice` combines:

- Cross-controller advice behavior from `@ControllerAdvice`.
- Response-body serialization behavior appropriate for JSON REST APIs.

Its `@ExceptionHandler` methods select behavior by exception type and return `ResponseEntity<ErrorResponse>` with an explicit status.

This keeps controllers focused on:

- Request binding.
- Input validation activation.
- Service delegation.
- Success responses.

Controllers do not need repetitive exception-catching and response-building code.

## 6. Validation Exception Handling

`RestaurantController.createRestaurant` uses `@Valid` with `RestaurantRequest`.

The validation flow is:

```text
HTTP JSON request
      ↓
Jackson request binding
      ↓
@Valid triggers Jakarta Bean Validation
      ↓
Validation fails
      ↓
MethodArgumentNotValidException
      ↓
GlobalExceptionHandler
      ↓
400 ErrorResponse
```

The handler uses Spring Framework 7-compatible `MethodArgumentNotValidException.getBindingResult()`.

### Field-level errors

Every `FieldError` is collected under its Java field name. For example:

```json
{
  "name": [
    "Restaurant name is required"
  ]
}
```

### Multiple validation errors

Each key maps to a list. This is important because one field can fail more than one constraint. A `Map<String, String>` would overwrite earlier messages for the same field.

The implementation includes all reported field errors and does not stop after the first failure.

### Global errors

Object-level validation failures that are not associated with one field are stored under:

```text
_global
```

This reserves a predictable location for future cross-field constraints.

### Fallback message

If Spring does not provide a default validation message, the handler uses:

```text
Invalid value
```

### Timezone validation key

The timezone check is implemented through the derived Bean Validation property `isTimezoneValid()`, which Bean Validation reports as `timezoneValid`. `GlobalExceptionHandler` normalizes only that known validation-property key to the client-facing request field `timezone`.

The validation rule, message, and `400 Bad Request` behavior are unchanged. Other field names and all global errors continue through the same collection logic without remapping.

## 7. Restaurant Not Found Handling

`RestaurantServiceImpl` throws `RestaurantNotFoundException` when `RestaurantRepository.findByPublicId()` returns empty.

The global handler:

- Returns `404 Not Found`.
- Uses the exception's safe message.
- Includes the failed request URI.
- Uses the same `ErrorResponse` structure.
- Returns an empty `errors` object because no field-level validation failed.

The service exception is not annotated with HTTP-specific annotations. HTTP translation remains in the web advice, allowing the service to remain usable outside REST.

## 8. HTTP Status Mapping

| Exception / condition | HTTP status | Implemented |
| --- | --- | --- |
| `MethodArgumentNotValidException` | `400 Bad Request` | Yes |
| `RestaurantNotFoundException` | `404 Not Found` | Yes |
| Defined state/uniqueness/concurrency conflict | `409 Conflict` | Deferred |
| Unexpected server failure | `500 Internal Server Error` | Deferred |

Mappings are based on understood exception semantics. The implementation does not broadly catch unrelated exceptions and guess their meaning.

## 9. API Response Examples

### 400 validation response

```json
{
  "timestamp": "2026-09-26T15:30:00Z",
  "status": 400,
  "message": "Validation failed",
  "path": "/api/v1/restaurants",
  "errors": {
    "name": [
      "Restaurant name is required"
    ],
    "contactEmail": [
      "Contact email must be a valid email address"
    ]
  }
}
```

### 404 restaurant-not-found response

```json
{
  "timestamp": "2026-09-26T15:31:00Z",
  "status": 404,
  "message": "Restaurant not found with publicId: 9f6fd8e8-5bc8-4dad-86af-fda324c093ec",
  "path": "/api/v1/restaurants/9f6fd8e8-5bc8-4dad-86af-fda324c093ec",
  "errors": {}
}
```

## 10. Why Centralized Handling Is Preferred

Centralized handling:

- Produces one predictable API contract.
- Keeps successful controller flows easy to read.
- Prevents duplicated status and response construction.
- Makes error policy easier to review and change.
- Allows future controllers to reuse the same behavior.
- Keeps service exceptions separate from HTTP concerns.

Controllers should generally allow known application exceptions to propagate to the advice rather than catching each one locally.

## 11. Security and Information Disclosure

API responses must not expose:

- Stack traces.
- Internal exception class names.
- SQL or database-driver messages.
- Database table or constraint details.
- Server filesystem paths.
- Secrets, tokens, or credentials.
- Sensitive request payloads.

Unexpected exceptions should eventually be logged on the server with an appropriate correlation identifier while clients receive only a safe generic response.

## 12. Testing Performed

Validation completed during implementation:

- `./mvnw clean compile` completed successfully.
- Thirteen Java source files compiled with Java 21.
- IDE lint checks reported no errors for the implementation and documentation.
- Git diff whitespace validation passed.

No new automated MVC test was added in this implementation step.

Future tests should verify:

- All field errors are included.
- Multiple messages for one field are preserved.
- Object-level errors use `_global`.
- Validation returns `400`.
- Missing restaurants return `404`.
- Both responses use the same schema.
- Stack traces and internal details are absent.

## 13. Deferred Error Handling

### Malformed JSON

Malformed request bodies can result in `HttpMessageNotReadableException`. A future safe `400` mapping should distinguish unreadable JSON from Bean Validation failures.

### Invalid UUID

A malformed UUID path variable can fail during Spring type conversion before the service executes. A future handler should return a safe `400` response using the same error structure.

### 409 conflicts

Future handlers may translate understood conflicts such as:

- Duplicate constrained data.
- Invalid lifecycle transitions.
- Optimistic-lock failures.

Raw `DataIntegrityViolationException` or database messages must not be returned directly.

### Generic 500 errors

A future generic handler should:

- Return a generic `500 Internal Server Error` message.
- Log the complete exception only on the server.
- Include a correlation identifier in logs and the client response.
- Record metrics and support alerting.
- Avoid masking specific framework exceptions that need dedicated mappings.

## 14. Production Evolution

Potential future improvements:

- Stable machine-readable error codes.
- Correlation/request identifiers.
- Structured server logging and distributed tracing.
- Message localization through `MessageSource`.
- Metrics grouped by status and error code.
- Safe handling for malformed input, conflicts, and unexpected failures.
- Shared MVC integration tests for the error contract.
- Documentation in future OpenAPI responses.

These enhancements should preserve the same principles: explicit semantics, predictable structure, minimal disclosure, and centralized policy.
