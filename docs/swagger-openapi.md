# Swagger/OpenAPI

## 1. Overview

The Swagger/OpenAPI integration provides interactive and machine-readable
documentation for the FoodSaver AI REST APIs. SpringDoc inspects the Spring MVC
controllers and publishes an OpenAPI description together with Swagger UI.

The application runs on port `8040`.

Implemented configuration:

- SpringDoc OpenAPI starter dependency.
- `OpenApiConfig` Spring configuration class.
- FoodSaver AI API title, description, and version metadata.
- Swagger UI and OpenAPI JSON endpoints.

## 2. Why Swagger/OpenAPI Is Used in FoodSaver AI

FoodSaver AI contains REST APIs for modules such as Restaurant Management and
Food/Product Management. A shared API description helps developers understand
available routes, request bodies, path variables, response models, and status
codes without manually reconstructing the contract from controller code.

OpenAPI also provides a standard contract that can later support client
generation, contract validation, external API portals, and automated tooling.
Swagger UI renders that contract as interactive browser documentation.

## 3. SpringDoc Dependency

The backend uses:

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

The WebMVC UI starter integrates with the current Spring MVC application,
generates the OpenAPI document, and serves Swagger UI. No additional dependency
or custom servlet is required for the current setup.

## 4. OpenAPI Configuration

`backend/src/main/java/com/foodsaver/config/OpenApiConfig.java` is a Spring
`@Configuration` class. It exposes an `OpenAPI` object as a Spring `@Bean`.

The configured metadata is:

- Title: `FoodSaver AI API`
- Description: `REST APIs for the FoodSaver AI platform. The platform helps
  restaurants manage restaurants, products, inventory, surplus food, offers,
  and orders.`
- Version: `v1.0.0`

The bean customizes API-level metadata while SpringDoc continues to discover
the controllers and DTO schemas automatically.

No OpenAPI security scheme is configured because Spring Security,
authentication, and authorization are not implemented yet. Controller-level
annotations such as `@Operation` and `@ApiResponse` have also been deferred.

## 5. Swagger UI Endpoint

With the application running locally on port `8040`, Swagger UI is available
at:

```text
http://localhost:8040/swagger-ui.html
```

SpringDoc may redirect this URL to its internal Swagger UI index page. The UI
loads the generated OpenAPI document and groups the discovered operations by
controller.

## 6. OpenAPI JSON Endpoint

The machine-readable OpenAPI document is available at:

```text
http://localhost:8040/v3/api-docs
```

This JSON describes the discovered paths, operations, request bodies,
parameters, response schemas, and component schemas. Tools can consume this
endpoint without using the visual Swagger UI.

## 7. Current API Documentation Coverage

SpringDoc currently discovers the implemented Spring MVC controllers,
including:

- Restaurant Management endpoints for creating a restaurant, retrieving one
  restaurant, and retrieving all restaurants.
- Food/Product Management endpoints for creating, retrieving, updating, and
  deleting a Product under its owning Restaurant.
- DTO schemas and Jakarta Bean Validation constraints that SpringDoc can infer.

Coverage is currently based primarily on controller method signatures, Spring
web annotations, DTO types, and validation annotations. Custom operation
summaries, detailed response examples, explicit error-response declarations,
tags, and controller-level Swagger annotations have not been added.

Documentation of an endpoint does not add authentication, authorization,
inventory, offers, orders, Kafka, Redis, AI, or food-safety behavior. Some of
those concepts appear in the general API description as platform goals but are
not current API implementations.

## 8. Using Swagger UI for API Testing

1. Start the backend and ensure its MySQL connection is available.
2. Open `http://localhost:8040/swagger-ui.html`.
3. Expand the required controller and operation.
4. Select **Try it out**.
5. Enter path variables such as `restaurantPublicId` and `productPublicId`.
6. Supply a JSON request body for POST or PUT operations.
7. Select **Execute**.
8. Review the generated curl command, request URL, response status, headers,
   and response body.

Swagger UI exercises the real local API. It does not mock the service or bypass
validation, ownership checks, persistence, transactions, or centralized
exception handling.

For Product operations, a valid Restaurant must exist. Ownership-aware GET,
PUT, and DELETE requests must use the Restaurant that owns the Product.

## 9. Benefits for Development and API Testing

- Provides discoverable documentation for current REST endpoints.
- Displays request and response schemas derived from Java DTOs.
- Allows quick manual testing without a separate API client.
- Makes path variables and required request fields visible.
- Helps frontend and backend developers discuss one API contract.
- Exposes the OpenAPI JSON for tooling and future client generation.
- Makes validation and error responses easier to inspect during development.
- Reduces reliance on undocumented endpoint knowledge.

Swagger UI is useful for exploratory verification, but it does not replace
automated unit, integration, contract, and security tests.

## 10. Current Scope and Future Improvements

Current scope:

- One application-level `OpenAPI` bean.
- Basic API title, description, and version.
- Default SpringDoc controller and schema discovery.
- Swagger UI and OpenAPI JSON endpoints.
- No security scheme.
- No controller-level OpenAPI annotations.

Possible future improvements:

- Add meaningful tags and operation summaries.
- Document success and error responses explicitly.
- Add examples for request, response, and `ErrorResponse` payloads.
- Document validation and ownership-related `400` and `404` responses.
- Introduce authentication metadata only after Spring Security is implemented.
- Decide whether API documentation endpoints should be restricted or disabled
  in production.
- Publish versioned OpenAPI documents as the API evolves.
- Add automated OpenAPI contract checks to CI.
- Generate client SDKs only after the API contract and compatibility policy are
  stable.

Future annotations must describe actual implemented behavior rather than
advertising planned functionality as available.

## 11. Verification and Testing Summary

The OpenAPI configuration was compiled with the Java 21 Maven backend:

```text
./mvnw clean compile
```

Compilation completed successfully. The configured integration exposes:

- Swagger UI: `http://localhost:8040/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8040/v3/api-docs`

The application port is configured as `8040`. The current integration has been
kept intentionally minimal: no Java controllers, DTOs, properties, or security
configuration were changed to add the API metadata bean.
