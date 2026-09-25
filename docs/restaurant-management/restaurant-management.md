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

The first persistence component is now implemented: the `Restaurant` JPA entity and its `BusinessType` and `RestaurantStatus` enums. No repository, DTO, service, controller, endpoint, security component, or database migration has been implemented yet.

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

## 7. Future API Requirements

The following endpoints describe the eventual contract. They are not implemented.

| Method | Proposed endpoint | Purpose | Expected access |
| --- | --- | --- | --- |
| `POST` | `/api/v1/restaurants` | Register a restaurant profile | Authenticated onboarding actor or controlled public onboarding |
| `GET` | `/api/v1/restaurants/{publicId}` | Retrieve an authorized management view | Restaurant member or administrator |
| `GET` | `/api/v1/restaurants` | Paginated administrative search/filter | Administrator |
| `PATCH` | `/api/v1/restaurants/{publicId}` | Partially update editable profile fields | Authorized restaurant member |
| `PATCH` | `/api/v1/restaurants/{publicId}/status` | Execute an explicit lifecycle transition | Administrator, except future self-deactivation policy |

### API behavior

- `POST` should return `201 Created` and a `Location` header.
- Successful reads should return `200 OK`.
- A successful profile update should return `200 OK` with the updated response or `204 No Content`; the implementation must choose one consistently.
- Invalid input should return `400 Bad Request` with field-level errors.
- Missing records should return `404 Not Found`.
- Duplicate constrained values or stale optimistic-lock updates should return `409 Conflict`.
- Authentication and authorization failures should use `401 Unauthorized` and `403 Forbidden` appropriately after security is introduced.
- List responses must be paginated, have a maximum page size, use a deterministic secondary sort, and avoid leaking private fields.
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

`Restaurant` remains in `com.foodsaver.entity`, while the reusable enum types are kept separately in `com.foodsaver.enums`. Repository, DTO, service, controller, exception handling, security, messaging, caching, AI, and food-safety logic remain unimplemented.

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

### 9.6 Remaining layers

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

No class from those layers was added in this step.

---

## 10. Exception and Error Requirements

The eventual module should integrate with centralized `@RestControllerAdvice` handling rather than duplicating `try/catch` blocks in controllers.

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

No new test class was added because this step was limited to the entity and enums.

Validation performed:

- `mvn -DskipTests compile` completed successfully.
- `mvn clean test` compiled the application and tests, then the existing `FoodSaverApplicationTests.contextLoads` test failed while creating the JPA context because a usable MySQL connection/JDBC metadata was not available in the execution environment.
- No `pom.xml` or application configuration was changed to bypass that infrastructure requirement.

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
