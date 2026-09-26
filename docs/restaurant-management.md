# Restaurant Management

## 1. Module Overview

The Restaurant Management module is the first business module of FoodSaver AI. It owns the core restaurant profile and platform-lifecycle information for participating food businesses such as restaurants, bakeries, sweet shops, and cafés.

This module provides the restaurant identity that later modules can reference:

- Food/Product Management
- Inventory Management
- Surplus Food Detection
- Food Safety Eligibility
- Discount Marketplace
- Customer Ordering
- AI Agents

The implemented foundation now includes the `Restaurant` JPA entity, its enums, repository, request/response DTOs, service layer, and REST controller. No global exception handler, security component, or database migration has been implemented yet.

### Scope boundary

This module manages restaurant identity, contact details, location, and platform status. It does **not** decide whether food is safe or eligible for sale. Food handling, safe-selling windows, surplus eligibility, and related approval rules belong to a later dedicated Food Safety Eligibility module.

---

## 2. Development Approach

The module will be developed incrementally in small, reviewable steps:

1. Requirements and database design
2. Entity and schema migration strategy
3. Repository
4. Request and response DTOs
5. Service contract and implementation
6. Controller and API validation
7. Centralized exception handling
8. Unit and integration testing
9. Security and authorization
10. Production hardening and documentation

The project rules require a layered structure:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

DTOs will define the API boundary. JPA entities will not be returned directly by controllers.

---

## 3. Business Requirements

### BR-01: Restaurant onboarding

The platform must allow a food business outlet to create a restaurant profile containing enough identity, contact, location, currency, and timezone information to participate in later platform workflows.

### BR-02: Restaurant identity

Every restaurant must have a stable internal identifier and a separate non-sequential public identifier suitable for URLs and external API responses.

### BR-03: Profile management

Authorized restaurant users must eventually be able to view and update editable profile details without changing system-managed identity, audit, or lifecycle fields.

### BR-04: Platform lifecycle

The platform must distinguish whether a restaurant is pending review, active, suspended, or inactive. Lifecycle status is an account/platform concern only; it is not a food-safety assessment.

### BR-05: Location and local time

The restaurant must have a structured postal address and IANA timezone so future modules can interpret opening times, offer expiry, pickup times, and reporting consistently.

### BR-06: Contactability

The platform must maintain a business contact email and phone number for operational communication. These values identify contact channels, not login credentials.

### BR-07: Future module ownership

Food items, inventory, offers, and orders must reference the restaurant by identifier instead of duplicating restaurant profile data.

### BR-08: Historical integrity

Restaurants should normally be deactivated rather than physically deleted after dependent business records exist. This preserves order, audit, and reporting history.

---

## 4. Functional Requirements

- **FR-01:** Register a restaurant using validated profile data.
- **FR-02:** Generate identifiers and audit timestamps on creation.
- **FR-03:** Default a new restaurant to `PENDING_VERIFICATION`.
- **FR-04:** Retrieve a restaurant by public identifier.
- **FR-05:** List restaurants using pagination and stable sorting.
- **FR-06:** Filter administrative lists by lifecycle status and location attributes where needed.
- **FR-07:** Update an allowed subset of profile fields.
- **FR-08:** Change status only through explicit, authorized lifecycle operations.
- **FR-09:** Reject invalid status transitions.
- **FR-10:** Prevent clients from setting system-managed fields such as `id`, `public_id`, audit timestamps, and optimistic-lock version.
- **FR-11:** Return a conflict response if a future validated business identifier violates an applicable unique constraint.
- **FR-12:** Preserve existing restaurant records by using inactive/suspended states rather than implementing hard deletion in the initial API.
- **FR-13:** Keep registration/profile operations independent of food-safety eligibility logic.
- **FR-14:** Record enough information to interpret future time-based and price-based operations using the restaurant's timezone and currency.

### Out of scope for this design step

- Authentication and the relationship between users and restaurants
- Restaurant ownership transfer and staff roles
- Operating-hours persistence
- Multiple branches under one legal organization
- Food/product, inventory, surplus, offer, and order management
- Food-safety or food-eligibility rules
- Geospatial nearby-search implementation
- Document upload and regulatory verification workflow
- Payment and tax calculation

These are valid future capabilities, but including them in the first restaurant table would couple the module to requirements that have not yet been defined.

---

## 5. Non-Functional Requirements

### Security and privacy

- Authenticate and authorize create, update, list, and status operations when the security module is introduced.
- Apply least privilege: restaurant operators should not perform administrative status changes.
- Validate API input independently of database constraints.
- Avoid exposing sequential internal IDs or unnecessary contact/audit data in public APIs.
- Never log secrets or full sensitive request payloads.

### Data integrity

- Enforce required fields, length limits, and unique constraints in the database as a final integrity layer.
- Use transactions for write operations.
- Use optimistic locking to detect conflicting concurrent updates.
- Store enum-like lifecycle values through an explicit stable representation rather than ordinal numbers.
- Store timestamps in UTC and interpret local business time using the IANA timezone.

### Performance and scalability

- Paginate all collection endpoints and enforce a maximum page size.
- Add indexes for demonstrated query patterns, while avoiding indexes on every column.
- Keep the initial record normalized and avoid large unstructured profile blobs.
- Plan geospatial indexing only when nearby-search requirements and MySQL spatial behavior are defined.

### Reliability and maintainability

- Return consistent error responses and meaningful validation messages.
- Make status transitions explicit and testable.
- Keep controller, service, repository, entity, and DTO responsibilities separate.
- Use database migrations before production instead of relying on `ddl-auto=update`.
- Emit structured logs and metrics without exposing personal or confidential data.

### API quality

- Version endpoints.
- Use appropriate HTTP methods and status codes.
- Make update behavior explicit and idempotent where appropriate.
- Provide stable response contracts independent of JPA implementation details.

No arbitrary uptime or latency SLA is assumed at this stage. Such targets require deployment and traffic requirements.

---

## 6. Database and Entity Mapping

### 6.1 Suggested table name

`restaurants`

The plural snake-case name is clear, conventional for SQL, and does not conflict with a reserved MySQL keyword.

### 6.2 Proposed fields

The following design is mapped by the implemented `Restaurant` entity. It is not yet managed by a versioned database migration; the current development configuration still relies on Hibernate schema update.

| Column | MySQL type | Intended Java type | Required | Default | Purpose and decision |
| --- | --- | --- | --- | --- | --- |
| `id` | `BIGINT` | `Long` | Yes | Database generated | Internal primary key for efficient joins. It should not be exposed as the public identity. |
| `public_id` | `CHAR(36)` | `UUID` | Yes | Application generated | Non-sequential stable identifier for URLs and APIs. `CHAR(36)` favors implementation clarity initially; binary UUID storage can be reconsidered only if measured scale justifies it. |
| `name` | `VARCHAR(150)` | `String` | Yes | None | Customer-facing outlet name. Required so the restaurant is identifiable in management and marketplace views. |
| `legal_name` | `VARCHAR(200)` | `String` | No | `NULL` | Registered legal business name when different from the display name. Optional because requirements vary by business and jurisdiction. |
| `business_type` | `VARCHAR(32)` | Enum | Yes | None | Coarse classification such as `RESTAURANT`, `BAKERY`, `SWEET_SHOP`, `CAFE`, or `OTHER`. Useful for onboarding and discovery without encoding food-safety behavior. |
| `description` | `VARCHAR(1000)` | `String` | No | `NULL` | Short public profile description. Length is bounded to avoid an unnecessary large-text field. |
| `contact_email` | `VARCHAR(254)` | `String` | Yes | None | Operational contact address. It is not a login identifier and therefore is not globally unique. |
| `contact_phone` | `VARCHAR(32)` | `String` | Yes | None | Operational contact number. The length supports E.164 formatting plus controlled presentation characters. It is not globally unique because branches may share a number. |
| `website_url` | `VARCHAR(2048)` | `String` | No | `NULL` | Optional official website. It must be syntactically validated and safely rendered by clients. |
| `address_line_1` | `VARCHAR(255)` | `String` | Yes | None | Primary street/premises address required for outlet identification and pickup. |
| `address_line_2` | `VARCHAR(255)` | `String` | No | `NULL` | Optional apartment, floor, landmark, or additional address detail. |
| `city` | `VARCHAR(100)` | `String` | Yes | None | Locality used for address display and initial administrative filtering. |
| `state_province` | `VARCHAR(100)` | `String` | Yes initially | None | Administrative area. Initial rollout assumes supported addresses require it; this must be revisited before supporting countries where it is not applicable. |
| `postal_code` | `VARCHAR(20)` | `String` | Yes initially | None | Postal identifier. Stored as text to preserve leading zeros and country-specific formats. |
| `country_code` | `CHAR(2)` | `String` | Yes | None | ISO 3166-1 alpha-2 country code. Avoids inconsistent free-text country names. |
| `latitude` | `DECIMAL(9,6)` | `BigDecimal` | No | `NULL` | Optional geocoded latitude for future nearby discovery. Six decimal places are sufficient for outlet-level coordinates. |
| `longitude` | `DECIMAL(9,6)` | `BigDecimal` | No | `NULL` | Optional geocoded longitude. It must be present together with latitude. |
| `timezone` | `VARCHAR(50)` | `String`/`ZoneId` | Yes | None | IANA timezone such as `Asia/Kolkata`; necessary for local operating and offer times. A fixed UTC offset is insufficient because offsets can change. |
| `currency_code` | `CHAR(3)` | `String`/`Currency` | Yes | None | ISO 4217 currency code used by future price and offer modules. Money amounts will still belong to those modules. |
| `pickup_instructions` | `VARCHAR(500)` | `String` | No | `NULL` | Optional customer-facing instructions for locating the pickup point. It must not contain safety eligibility policy. |
| `status` | `VARCHAR(32)` | Enum | Yes | `PENDING_VERIFICATION` | Platform lifecycle state. Proposed values: `PENDING_VERIFICATION`, `ACTIVE`, `SUSPENDED`, and `INACTIVE`. |
| `version` | `BIGINT` | `Long` | Yes | `0` | Optimistic-lock value used to detect lost updates. |
| `created_at` | `TIMESTAMP(6)` | `Instant` | Yes | Application/persistence generated | UTC audit timestamp for record creation. |
| `updated_at` | `TIMESTAMP(6)` | `Instant` | Yes | Application/persistence generated | UTC audit timestamp for the latest successful update. |

### 6.3 Fields deliberately not included yet

- **Password or JWT data:** authentication belongs to a user/security model, never the restaurant profile.
- **Owner user ID:** user-to-restaurant membership and roles require a separate, not-yet-designed association.
- **Food-safety status or safe-selling window:** belongs to later food/product, inventory-batch, and eligibility modules.
- **Opening and closing times:** should use a child schedule model once requirements cover day-of-week, split shifts, holidays, and exceptions.
- **Business registration/tax number:** jurisdiction and identifier type must be defined first. A generic globally unique field would be incorrect.
- **Logo binary data:** assets should eventually be handled through controlled object storage and an asset reference, not a large database column.
- **Soft-delete timestamp:** the initial lifecycle status provides deactivation without adding soft-delete query complexity. Revisit if legal retention/deletion requirements demand it.

### 6.4 Proposed unique constraints

| Constraint | Columns | Reason |
| --- | --- | --- |
| Primary key | `id` | Efficient internal identity and joins. |
| `uk_restaurants_public_id` | `public_id` | Guarantees that the public API identity resolves to exactly one restaurant. |

The following fields must **not** initially be unique:

- `name`: unrelated restaurants and chain branches can share display names.
- `contact_email`: one operations mailbox may serve multiple outlets.
- `contact_phone`: one contact number may serve multiple outlets.
- Postal address: multiple businesses may legally share a building or formatted address.

A future business registration identifier should be unique only within its defined identifier type and issuing jurisdiction. That requirement is intentionally deferred rather than represented by an unsafe global constraint.

### 6.5 Suggested indexes

| Index | Columns | Reason |
| --- | --- | --- |
| `PRIMARY` | `id` | Created by the primary key. |
| `uk_restaurants_public_id` | `public_id` | Unique public-ID lookup; also serves as its lookup index. |
| `idx_restaurants_status_id` | `status`, `id` | Supports stable paginated administrative lists filtered by status. |
| `idx_restaurants_country_city_status` | `country_code`, `city`, `status` | Supports initial location/status filtering without claiming full nearby-search support. |

Do not add separate indexes for low-selectivity or currently unused fields. `created_at`, `contact_email`, or other indexes should be added only when an implemented query requires them and query plans justify them.

A normal composite B-tree on latitude/longitude is not a substitute for geospatial search. When nearby offers are implemented, evaluate MySQL `POINT` with an SRID and a spatial index, or an appropriate search service, based on measured requirements.

### 6.6 Lifecycle model

The implemented `RestaurantStatus` values are:

- `PENDING_VERIFICATION`: profile exists but is not approved for normal platform activity.
- `ACTIVE`: restaurant may participate in authorized platform workflows.
- `SUSPENDED`: platform access is temporarily blocked by an authorized administrative action.
- `INACTIVE`: restaurant has been voluntarily or administratively deactivated while history is retained.

Allowed transitions must be defined in service-layer policy rather than inferred from enum order. This status does not indicate whether any food item is safe, unsafe, surplus, or eligible.

### 6.7 Concurrency and audit implementation

`version` is annotated with `@Version`, enabling Hibernate optimistic locking so two simultaneous profile updates do not silently overwrite each other. A future service/API layer should translate a stale update into a conflict response rather than lose data.

`createdAt` and `updatedAt` use `Instant`. A `@PrePersist` callback initializes both timestamps immediately before the first insert, and a `@PreUpdate` callback refreshes `updatedAt` before an update. `created_at` is non-updatable. Actor-based fields such as `created_by` and `updated_by` are deferred until user identity and authorization are designed; adding them now without a valid actor model would create misleading nullable data.

---

## 7. REST API

The initial controller implements the three current-phase endpoints. Update, status-change, and delete operations remain deferred.

| Method | Endpoint | Current behavior | Status |
| --- | --- | --- | --- |
| `POST` | `/api/v1/restaurants` | Validate and create a restaurant | Implemented |
| `GET` | `/api/v1/restaurants/{publicId}` | Retrieve a restaurant by public UUID | Implemented |
| `GET` | `/api/v1/restaurants` | Retrieve all restaurants | Implemented for the current phase; pagination deferred |
| `PATCH` | `/api/v1/restaurants/{publicId}` | Partially update editable profile fields | Deferred |
| `PATCH` | `/api/v1/restaurants/{publicId}/status` | Execute an authorized lifecycle transition | Deferred |
| `DELETE` | `/api/v1/restaurants/{publicId}` | Deactivate/delete according to a defined retention policy | Deferred |

### API behavior

- `POST` returns `201 Created` with `RestaurantResponse` in the body. A `Location` header is not added in this phase.
- Both successful `GET` operations return `200 OK`.
- A successful profile update should return `200 OK` with the updated response or `204 No Content`; the implementation must choose one consistently.
- Invalid input should return `400 Bad Request` with field-level errors.
- Missing records currently propagate `RestaurantNotFoundException`; mapping it to `404 Not Found` is deferred to the global exception-handling phase.
- Duplicate constrained values or stale optimistic-lock updates should return `409 Conflict`.
- Authentication and authorization failures should use `401 Unauthorized` and `403 Forbidden` appropriately after security is introduced.
- The current list response is intentionally unpaginated to match the current service contract. Pagination, maximum page size, deterministic sorting, and filtering are required before production-scale use.
- Status changes should use a dedicated request contract and service operation, not a general profile update.
- Hard deletion is intentionally omitted from the initial API.

Public marketplace restaurant views will likely need a separate response projection or endpoint because public consumers should not receive management-only contact, audit, or status details.

---

## 8. Validation Requirements

Validation must exist at both the API boundary and the database boundary. API validation gives useful feedback; database constraints protect integrity during races or non-HTTP writes.

### Text and enum validation

- Trim surrounding whitespace before persistence.
- Reject blank required strings.
- Apply the maximum lengths defined in the field table.
- Restrict `business_type` and `status` to supported values; do not accept arbitrary enum names.
- Do not persist empty optional strings when `NULL` expresses absence consistently.
- Decide and test Unicode normalization for names and addresses.

### Email, phone, and URL validation

- Validate `contact_email` syntax and normalize the domain portion to lowercase. Do not assume the whole local part is case-insensitive or that syntax proves ownership.
- Accept international phone input and normalize to a canonical format, preferably E.164, when possible. Do not encode one country's fixed digit count.
- Allow only approved URL schemes such as `https` (and `http` only if explicitly accepted for development). Clients must safely render the URL.

### Address and code validation

- Validate `country_code` against supported uppercase ISO 3166-1 alpha-2 values.
- Validate `currency_code` against supported uppercase ISO 4217 values.
- Validate `timezone` using `ZoneId`, not only a regex.
- Validate postal code according to the selected rollout countries; do not use one universal numeric regex.
- Require `state_province` and `postal_code` under the current rollout assumption, with country-aware rules before international expansion.

### Coordinate validation

- `latitude` and `longitude` must either both be present or both be absent.
- Latitude must be between `-90` and `90`.
- Longitude must be between `-180` and `180`.
- Coordinates should come from a trusted geocoding or verified user flow before they are used for customer-facing nearby results.

### Lifecycle and update validation

- A new client request cannot select `ACTIVE`, `SUSPENDED`, or `INACTIVE`; initial status is server controlled.
- Clients cannot modify IDs, audit timestamps, or `version` as ordinary profile data.
- Status transitions require authorization and must follow an explicit transition policy.
- Update requests must distinguish “not supplied” from “set to null” where nullable fields are patchable.
- A stale version or equivalent conditional update must not overwrite a newer change.

### Cross-module safety boundary

No validation in this module should infer food safety from restaurant type, status, address, or profile completeness. `ACTIVE` means the restaurant account is active; it does not make any food item eligible for sale.

---

## 9. Entity and Enums

### 9.1 Implemented files

- `backend/src/main/java/com/foodsaver/entity/Restaurant.java`
- `backend/src/main/java/com/foodsaver/enums/BusinessType.java`
- `backend/src/main/java/com/foodsaver/enums/RestaurantStatus.java`
- `backend/src/main/java/com/foodsaver/repository/RestaurantRepository.java`
- `backend/src/main/java/com/foodsaver/dto/request/RestaurantRequest.java`
- `backend/src/main/java/com/foodsaver/dto/response/RestaurantResponse.java`
- `backend/src/main/java/com/foodsaver/service/RestaurantService.java`
- `backend/src/main/java/com/foodsaver/service/impl/RestaurantServiceImpl.java`
- `backend/src/main/java/com/foodsaver/exception/RestaurantNotFoundException.java`
- `backend/src/main/java/com/foodsaver/controller/RestaurantController.java`

The module now follows the intended entity, enum, repository, DTO, service, service implementation, exception, and controller package boundaries. Mapping currently remains private to the service implementation; no standalone mapper, global exception handling, security, messaging, caching, AI, or food-safety logic has been introduced.

### 9.2 Core JPA mapping

- `@Entity` marks `Restaurant` as a JPA-managed persistent type.
- `@Table(name = "restaurants")` maps it to the approved table name and declares the named public-ID unique constraint and approved indexes.
- `@Id` identifies the internal `Long id` primary key.
- `@GeneratedValue(strategy = GenerationType.IDENTITY)` delegates internal ID generation to the MySQL identity/auto-increment mechanism.
- The internal ID has no Lombok-generated setter and is not intended to become an external API identifier.
- `publicId` is a non-null, non-updatable `UUID` populated in `@PrePersist` with `UUID.randomUUID()`.
- `@JdbcTypeCode(SqlTypes.CHAR)` makes the Hibernate/MySQL mapping follow the approved readable `CHAR(36)` UUID representation rather than a provider-default binary UUID mapping.
- The named `uk_restaurants_public_id` table constraint guarantees public identifier uniqueness.

### 9.3 Field and enum mapping

All approved profile fields are mapped from Java camelCase names to explicit snake_case columns with the documented nullability and lengths. Optional fields omit `nullable = false`. `latitude` and `longitude` use nullable `BigDecimal` values with precision `9` and scale `6`; cross-field coordinate validation is intentionally deferred to a later validation step.

`BusinessType` implements:

```text
RESTAURANT
BAKERY
SWEET_SHOP
CAFE
OTHER
```

`RestaurantStatus` implements:

```text
PENDING_VERIFICATION
ACTIVE
SUSPENDED
INACTIVE
```

Both enum fields use `@Enumerated(EnumType.STRING)`. Persisted values therefore remain readable and do not depend on declaration order. Renaming a persisted enum constant would still require a deliberate data migration.

### 9.4 Lifecycle callbacks

The `@PrePersist` callback:

1. Generates `publicId` if it has not already been generated.
2. Restores the server-controlled default `PENDING_VERIFICATION` status if status is null.
3. Sets `createdAt` and `updatedAt` from one `Instant.now()` value.

The `@PreUpdate` callback refreshes only `updatedAt`. These callbacks provide entity-level timestamp generation without enabling additional Spring Data auditing configuration. A production deployment should still define a consistent JDBC/database timezone and verify the physical timestamp type through migrations.

### 9.5 Lombok decision

The project already includes Lombok. `@Getter`, `@Setter`, and `@NoArgsConstructor` reduce repetitive entity code without introducing a dependency. Setters are suppressed for `id`, `publicId`, `version`, `createdAt`, and `updatedAt` because these fields are persistence- or lifecycle-managed.

`@Data`, generated `equals/hashCode`, builders, and all-arguments constructors were intentionally avoided. Entity identity and equality require a deliberate policy, and including mutable fields or lazy relationships in equality later can create persistence bugs.

### 9.6 Repository implementation

`RestaurantRepository` extends `JpaRepository<Restaurant, Long>`.

- `Restaurant` is the managed entity type.
- `Long` is the repository ID type because the field annotated with `@Id` is the internal `Long id`. Repository generic parameters follow the JPA primary key, not the separate public identifier.
- Extending `JpaRepository` provides the standard persistence operations needed by future services without writing a repository implementation manually.
- No `@Repository` annotation is necessary on this interface. Spring Data discovers repository interfaces under the application package and creates a proxy; repository exceptions are handled through Spring's persistence exception translation infrastructure.

The only application-specific method added is:

```java
Optional<Restaurant> findByPublicId(UUID publicId);
```

Spring Data parses `findByPublicId` as a derived query: `findBy` is the query subject and `PublicId` resolves to the existing `Restaurant.publicId` Java property. The property type is `UUID`, so the method parameter also uses `UUID`. The method returns `Optional<Restaurant>` because a lookup may find no row, while the database unique constraint guarantees that at most one row can match.

The method uses the Java entity property name, not the physical `public_id` column name. No SQL, JPQL, `@Query`, or unnecessary repository operation was added.

### 9.7 DTO implementation

`RestaurantRequest` contains only fields supplied by a client when creating or fully updating restaurant profile data. It deliberately excludes `id`, `publicId`, `status`, `version`, `createdAt`, and `updatedAt` because those values are managed by persistence or the application lifecycle.

Required request strings use `@NotBlank`; optional and required strings use `@Size` with the limits approved for the entity design. Other implemented constraints are:

- `@NotNull` for `businessType`.
- `@Email` for contact email syntax.
- `@Pattern("^\\+[1-9]\\d{1,14}$")` for canonical, international E.164-oriented telephone input.
- An HTTP/HTTPS `@Pattern` for the optional website URL.
- Uppercase two- and three-letter patterns for ISO-style country and currency codes.
- `@DecimalMin` and `@DecimalMax` for latitude and longitude ranges.
- `@AssertTrue` on a derived, Jackson-ignored validation property that verifies the timezone against Java's available IANA `ZoneId` values.

The timezone helper returns true for null or blank input so `@NotBlank` remains responsible for the required-field message. `@JsonIgnore` prevents the derived validation property from becoming part of the JSON contract.

`RestaurantResponse` exposes the approved public/management representation: public ID, profile data, business type, status, and creation/update timestamps. It excludes the internal database `id` and optimistic-lock `version`. Version exposure is deferred until an explicit API concurrency contract such as ETags or conditional requests is designed.

Both DTOs are plain Lombok-backed data carriers with getters, setters, and a no-argument constructor. They contain no persistence annotations and are separate from the JPA entity. No entity/DTO mapping logic or mapper class was introduced.

#### Intentionally deferred validation

- Latitude/longitude paired presence remains a cross-field rule for a later validation step.
- The country and currency patterns validate ISO-style shape only; membership in the supported ISO code sets and supported-market policy are deferred.
- The website pattern restricts the scheme and rejects whitespace but does not prove that the URL is reachable, safe, or owned by the restaurant.
- Phone ownership and deliverability cannot be proven by syntax validation.
- Postal-code rules remain country-specific and are deferred until supported countries are finalized.
- Because one request DTO has required fields, it is suitable for creation or full profile updates. A future partial `PATCH` contract will need a separate update DTO or an explicit field-presence strategy; weakening required validation here would make creation unsafe.
- Food-safety eligibility validation is not part of these DTOs.

### 9.8 Service implementation

`RestaurantService` defines three API-facing operations:

```java
RestaurantResponse createRestaurant(RestaurantRequest request);
RestaurantResponse getRestaurantByPublicId(UUID publicId);
List<RestaurantResponse> getAllRestaurants();
```

The interface separates the application contract from its implementation and lets a future controller depend on the service abstraction rather than persistence details.

`RestaurantServiceImpl` is marked with `@Service`, implements the interface, and receives its required `RestaurantRepository` through an explicit constructor. The repository field is `final`; field injection and `@Autowired` are not used. Constructor injection makes the dependency mandatory and keeps the class straightforward to instantiate in unit tests.

#### Create flow

1. Map the client-controlled fields from `RestaurantRequest` to a new `Restaurant`.
2. Explicitly set `RestaurantStatus.PENDING_VERIFICATION`.
3. Call `RestaurantRepository.save()`.
4. Allow the entity's `@PrePersist` callback to generate `publicId` and audit timestamps; the service does not generate them.
5. Map the saved entity to `RestaurantResponse`.

The request has no status field, so a client cannot choose the initial lifecycle state. The service also never maps internal `id` or `version` into the response.

#### Get-by-publicId flow

The service calls `RestaurantRepository.findByPublicId(UUID)`, maps the returned entity when present, and otherwise throws `RestaurantNotFoundException`. The exception is a minimal service-level runtime exception containing the missing public ID. HTTP status mapping and a standardized error body are deliberately deferred to the later global exception-handling step.

#### Get-all flow

The service calls `RestaurantRepository.findAll()`, maps each entity through the same response-mapping method, and returns a `List<RestaurantResponse>`. This meets the current phase requirement. Pagination and sorting should replace the unbounded list before this operation is used against production-scale data.

#### Mapping responsibility

Two private methods in `RestaurantServiceImpl` perform request-to-entity and entity-to-response mapping. Centralizing both mappings in one implementation keeps all three operations consistent while respecting the requirement not to create a mapper class yet. Mapping should move to a dedicated component only when reuse or complexity justifies it.

No update mapping was added. `RestaurantRequest` has create/full-update validation and is not suitable for partial PATCH semantics. No status-transition operation was added because authorization and allowed-transition policies remain undefined.

#### Transaction boundaries

`RestaurantServiceImpl` uses class-level `@Transactional(readOnly = true)` as the default for the two query operations. `createRestaurant` overrides that default with method-level `@Transactional`, enabling a normal read-write transaction around entity creation, `save()`, lifecycle callbacks, and response mapping.

The annotations are placed on the implementation where Spring executes the methods. They are not added blindly to private mapping helpers, which do not define independent transactional units.

### 9.9 Controller implementation

`RestaurantController` is marked with `@RestController` and uses the class-level base path `@RequestMapping("/api/v1/restaurants")`. It handles only HTTP concerns: request binding, request validation, path-variable conversion, delegation to `RestaurantService`, and explicit response status/body construction.

The controller receives `RestaurantService` through an explicit constructor and stores it in a `final` field. It does not use field injection or `@Autowired`. It depends on the service abstraction and never accesses `RestaurantRepository` directly, keeping transaction and application behavior out of the HTTP layer.

#### POST `/api/v1/restaurants`

- `@PostMapping` uses the class-level base path.
- `@RequestBody` deserializes JSON into `RestaurantRequest`.
- `@Valid` triggers Jakarta Bean Validation before the service is invoked.
- The controller delegates to `restaurantService.createRestaurant(request)`.
- `ResponseEntity.status(HttpStatus.CREATED).body(response)` returns `201 Created` and the created `RestaurantResponse`.
- The request DTO contains no internal `id`, `publicId`, status, version, or audit timestamps, and the controller does not generate or accept them separately.

Validation rules remain in `RestaurantRequest`; the controller does not duplicate them. If validation fails, Spring MVC raises its standard validation exception before entering the method body. A consistent validation-error response will be defined with the deferred global exception handler.

#### GET `/api/v1/restaurants/{publicId}`

- `@GetMapping("/{publicId}")` binds the route.
- `@PathVariable("publicId") UUID publicId` uses Spring conversion from the path segment to `UUID`.
- The controller delegates to `restaurantService.getRestaurantByPublicId(publicId)`.
- `ResponseEntity.ok(response)` returns `200 OK`.

`RestaurantNotFoundException` is intentionally not caught in the controller. It propagates until the global HTTP exception strategy is implemented.

#### GET `/api/v1/restaurants`

- `@GetMapping` uses the class-level base path.
- The controller delegates to `restaurantService.getAllRestaurants()`.
- `ResponseEntity.ok(responses)` returns `200 OK` with `List<RestaurantResponse>`.

This endpoint currently has no pagination, sorting, or filtering because those capabilities are outside this implementation step. They remain required before production-scale exposure.

#### Separation of responsibilities

The controller contains no entity mapping, persistence access, transaction handling, identifier generation, lifecycle decisions, or exception-catching boilerplate. Those responsibilities belong to DTO validation, the service/entity layers, repositories, and the future centralized error handler. This separation keeps HTTP code small and makes business behavior reusable outside REST.

No PATCH, DELETE, status-change, authentication, authorization, Swagger/OpenAPI, global exception handler, or integration-specific endpoint was introduced.

### 9.10 Remaining layers

The remaining implementation must continue to follow the project package structure:

```text
controller
service
service.impl
repository
entity
dto.request
dto.response
exception
config
```

No mapper, global exception handler, security, configuration, messaging, caching, or AI class was added in this step.

---

## 10. Exception and Error Requirements

`RestaurantNotFoundException` is implemented as the minimal service-level failure for a missing public ID. It extends `RuntimeException`, allowing the active transaction to follow Spring's normal rollback rules without forcing repository or interface callers to declare a checked exception.

The controller currently allows this and other application failures to propagate. A future centralized `@RestControllerAdvice` should map them without duplicating `try/catch` blocks in controller methods. No HTTP status annotation or global handler is added yet because the HTTP error contract remains a separate design step.

Expected error categories include:

- Request validation failure
- Restaurant not found
- Invalid lifecycle transition
- Duplicate constrained value
- Concurrent/stale update
- Authentication failure
- Authorization failure
- Unexpected internal failure with no implementation details leaked

An error response should contain a stable machine-readable code, safe human-readable message, timestamp, request/correlation identifier, and field errors when relevant.

---

## 11. Testing and Validation

No new test class was added because this step was limited to controller implementation and documentation.

Validation performed:

- `mvn -DskipTests compile` completed successfully.
- `mvn clean test` compiled the application and tests, then the existing `FoodSaverApplicationTests.contextLoads` test failed while creating the JPA context because a usable MySQL connection/JDBC metadata was not available in the execution environment.
- No `pom.xml` or application configuration was changed to bypass that infrastructure requirement.
- After adding `RestaurantRepository`, `mvn clean compile` completed successfully and compiled five source files.
- After adding the request and response DTOs, `mvn clean compile` completed successfully and compiled seven source files.
- After adding the service interface, implementation, and minimal not-found exception, `mvn clean compile` completed successfully and compiled ten source files.
- After adding `RestaurantController`, `./mvnw clean compile` completed successfully and compiled eleven source files.

Future implementation should include:

- Unit tests for profile rules, normalization, and lifecycle transitions.
- Validation tests for boundaries, nullability, malformed values, and cross-field coordinates.
- Repository integration tests for unique constraints, indexes/query methods, and optimistic locking.
- Controller tests for status codes, response shape, malformed JSON, validation errors, and authorization.
- Concurrency tests proving stale updates do not overwrite newer data.
- Migration tests once a migration tool is adopted.
- Tests proving restaurant status is not interpreted as food eligibility.

---

## 12. Production Considerations

- Replace `spring.jpa.hibernate.ddl-auto=update` with versioned Flyway or Liquibase migrations before production.
- Keep credentials externalized; the active `backend/src/main/resources/application.properties` already references environment variables.
- Define authenticated user-to-restaurant membership before implementing authorization.
- Consider data retention, correction, and deletion requirements for business contact data.
- Avoid physical deletion when dependent inventory, offers, or orders exist.
- Document timezone behavior and test daylight-saving transitions even if the first market does not currently observe them.
- Add rate limits and abuse controls to onboarding and search endpoints.
- Protect public lookup endpoints against enumeration and excessive data exposure.
- Add structured audit events for status changes when actor identity exists.
- Measure query plans before adding more indexes.
- Revisit `CHAR(36)` UUID storage only when actual scale and operational evidence justify binary conversion.
- Keep the Hibernate-specific `@JdbcTypeCode` mapping covered by persistence tests when database-backed tests are introduced.

---

## 13. Design Decisions and Alternatives

### Internal ID plus public UUID

A numeric primary key keeps joins and indexes compact. A UUID provides an external identifier that does not expose predictable record counts. Using only a UUID as the clustered primary key would simplify identity but increase index size and can cause poor insertion locality depending on UUID strategy.

### Structured address in the initial table

The initial design assumes one outlet per restaurant record and keeps its primary address on that record. A separate address table would add indirection without a current requirement for multiple addresses. If billing, registered-office, and pickup addresses are introduced, addresses should become a dedicated model.

### One restaurant record per outlet

Operational inventory, pickup, timezone, and future offers belong to a physical outlet. A multi-branch legal organization can later be modeled above restaurants instead of making one row represent several locations ambiguously.

### No globally unique email or phone

Business contact details are not account credentials. Shared support mailboxes and phone lines are normal, so uniqueness would incorrectly block valid branches.

### No hard delete initially

An explicit inactive state protects historical references and is easier to audit. This does not eliminate future legal deletion or anonymization requirements, which need a broader retention design.

### No operating-hours JSON

JSON would make initial storage easy but complicate validation and querying. A normalized schedule with exception dates should be designed when operating-hours behavior is in scope.

---

## 14. Assumptions and Open Decisions

### Assumptions

1. The active backend project is the Maven project rooted at `backend/`.
2. MySQL remains the primary relational database.
3. Each restaurant record represents one physical outlet, not an entire multi-location chain.
4. The initial supported market requires `state_province` and `postal_code`.
5. Business contact email and phone can be shared by multiple outlets.
6. Authentication, staff roles, and ownership are separate future concerns.
7. Public APIs will use `public_id`; internal relationships will use `id`.
8. Timestamps are stored as UTC instants and displayed using `timezone`.
9. Restaurant status controls platform participation only.
10. No food-safety policy is part of this module.

### Decisions still required before implementation

- Initial supported countries and their address/postal validation policies
- Whether onboarding is public, invitation-only, or administrator-created
- Who may update profile fields and who may perform each status transition
- Whether legal/business registration information is required for activation
- Supported business-type values and whether they should be configurable
- Whether profile changes require review before becoming publicly visible
- The exact API error contract and pagination convention
- Migration tool selection

---

## 15. Common Mistakes to Avoid

- Treating restaurant `status=ACTIVE` as proof that food is safe or eligible.
- Using restaurant name, email, or phone as a primary or globally unique identity.
- Storing a postal code as a number and losing leading zeros.
- Storing timezone as a fixed offset.
- Accepting latitude without longitude or coordinates outside valid ranges.
- Exposing JPA entities directly from REST controllers.
- Allowing clients to set IDs, audit fields, or activation status during registration.
- Using enum ordinals in the database.
- Omitting pagination from list endpoints.
- Adding indexes without an implemented query or query-plan evidence.
- Hard-deleting a restaurant that has dependent business history.
- Relying only on Bean Validation while omitting database constraints.

---

## 16. Interview Concepts Introduced

- Business, functional, and non-functional requirements
- Internal primary keys versus public identifiers
- Database nullability, uniqueness, and indexing
- Structured international addresses
- ISO country/currency codes and IANA timezones
- Optimistic locking
- Entity/DTO separation
- Layered architecture
- API versioning, pagination, and status codes
- Database migrations versus Hibernate schema update
- Lifecycle state machines
- Validation at API and database boundaries
- Module boundaries and separation of food-safety eligibility
- JPA entity and table mapping
- Identity generation and public UUIDs
- String enum persistence
- JPA lifecycle callbacks
- Instant and decimal coordinate persistence
