# Inventory Management

## 1. Module Overview

The Inventory Management module records the quantity and lifecycle state of a
restaurant's Product for a specific inventory date. It is the first layer that
represents how much of a prepared Product exists and how that quantity is
allocated.

The currently implemented module includes:

- `Inventory` JPA entity
- `InventoryStatus` enum
- `InventoryRepository`
- Inventory request and response DTOs
- `InventoryService` and `InventoryServiceImpl`
- `InventoryAlreadyExistsException`
- Create Inventory REST API
- Centralized `409 Conflict` handling
- OpenAPI documentation for the create operation

The current scope is deliberately small. It creates the initial daily
inventory record; it does not yet expose read, quantity-adjustment, status
transition, update, or delete operations.

## 2. Product vs Inventory

`Product` and `Inventory` represent different business concepts:

- **Product** is the restaurant's catalog or menu item. It contains relatively
  stable descriptive and pricing information such as name, category, base
  price, and currency.
- **Inventory** represents dated quantity and lifecycle state for that Product.
  It records how much was prepared and how that quantity is currently divided
  between available, reserved, and sold quantities.

Inventory fields are not stored in Product because catalog data and operational
stock change at different rates and have different lifecycle rules. A Product
can therefore exist without an Inventory record, and the same Product can have
different Inventory records on different dates.

## 3. Domain Relationship

The current relationship is:

```text
Restaurant
    └── Product
            └── Inventory by inventoryDate
```

`Inventory` directly references both `Restaurant` and `Product` using required,
lazy `@ManyToOne` relationships:

- `restaurant_id` identifies the Restaurant that owns the Inventory.
- `product_id` identifies the catalog item represented by the Inventory.

The service does not trust the two references independently. It resolves the
Restaurant first and then looks up the Product using both the Product public ID
and the Restaurant internal ID. This verifies that the Product belongs to the
Restaurant in the API path before an Inventory record can be created.

No bidirectional Inventory collections were added to Restaurant or Product.

## 4. Inventory Entity

Inventory is persisted in the `inventory` table.

### Fields

- `id` (`Long`): internal database primary key generated with identity
  strategy. It is not exposed through the API.
- `publicId` (`UUID`): application-generated identifier intended for external
  API use. It is stored as `CHAR(36)`, is not updatable, and has no public
  setter.
- `restaurant` (`Restaurant`): required owning Restaurant, mapped through
  `restaurant_id` with lazy loading.
- `product` (`Product`): required Product, mapped through `product_id` with
  lazy loading.
- `preparedQuantity` (`BigDecimal`): total quantity prepared for this record.
- `availableQuantity` (`BigDecimal`): quantity currently available for future
  business operations.
- `reservedQuantity` (`BigDecimal`): quantity allocated to reservations.
- `soldQuantity` (`BigDecimal`): quantity recorded as sold.
- `inventoryDate` (`LocalDate`): business date represented by the record.
- `status` (`InventoryStatus`): inventory lifecycle state, stored using
  `EnumType.STRING`.
- `version` (`Long`): JPA optimistic-lock version.
- `createdAt` (`Instant`): system-managed creation timestamp.
- `updatedAt` (`Instant`): system-managed last-update timestamp.

All quantity columns use precision `12` and scale `3`.

### Lifecycle callbacks

`@PrePersist`:

- Generates `publicId` using `UUID.randomUUID()` when absent.
- Defaults status to `ACTIVE` when absent.
- Initializes `createdAt`.
- Sets `updatedAt`.

`@PreUpdate` refreshes `updatedAt`.

The lifecycle callbacks do not perform quantity calculations or introduce
food-safety decisions.

## 5. Quantity Model and Invariant

The quantity model contains:

- `preparedQuantity`: the original total quantity represented by the record.
- `availableQuantity`: the portion not currently reserved or sold.
- `reservedQuantity`: the portion reserved by future controlled operations.
- `soldQuantity`: the portion completed as sold.

The required invariant is:

```text
preparedQuantity =
    availableQuantity + reservedQuantity + soldQuantity
```

During creation, the service initializes:

```text
preparedQuantity  = request.preparedQuantity
availableQuantity = request.preparedQuantity
reservedQuantity  = 0
soldQuantity      = 0
```

For example:

```text
50.000 = 50.000 + 0 + 0
```

This establishes the invariant when the Inventory is created. Future reserve,
release, sale, cancellation, or adjustment operations must preserve it inside
transactional service methods. The current module does not expose arbitrary
client updates to the quantity breakdown.

## 6. Why BigDecimal Is Used

Food quantities can be fractional, such as kilograms, litres, portions, or
other measured units. `BigDecimal` avoids binary floating-point precision
problems associated with `float` and `double`.

The database scale of three supports values such as `0.125` or `12.500`.
`BigDecimal.ZERO` is used for initial reserved and sold quantities instead of
floating-point literals.

The current model does not define a unit-of-measure field or unit conversion.
Those decisions remain future requirements and must not be inferred from the
numeric value alone.

## 7. InventoryStatus

The implemented lifecycle values are:

- `ACTIVE`: the Inventory record is active.
- `DEPLETED`: the inventory quantity lifecycle has reached depletion.
- `CLOSED`: the Inventory record has been operationally closed.

`InventoryStatus` is stored as a string to keep database values readable and
avoid ordinal-coupling problems.

### Food-safety boundary

Inventory status is **not** food-safety status.

`ACTIVE`, `DEPLETED`, and `CLOSED` describe inventory lifecycle only. They do
not mean `SAFE`, `UNSAFE`, `SPOILED`, or eligible for sale. No food-safety field
or food-safety rule is implemented in this module.

The FoodSaver AI rule remains:

> Surplus food is not automatically unsafe, and unsafe food is not
> automatically eligible for sale.

Future eligibility and safety decisions must use dedicated deterministic rules
and appropriate oversight. They must not be inferred solely from Inventory
status or quantity.

## 8. Public and Internal Identifiers

The internal `Long id` is efficient for database primary keys and foreign-key
relationships. It is a persistence detail and is not returned to clients.

The API exposes UUID public identifiers:

- `InventoryResponse.publicId`
- `InventoryResponse.restaurantPublicId`
- `InventoryResponse.productPublicId`

Using public UUIDs keeps clients independent from internal database sequences
and avoids exposing predictable numeric identifiers. UUIDs do not replace
authentication or authorization; those concerns are not implemented yet.

## 9. Database Integrity and Concurrency

### Unique business rule

The table constraint
`uk_inventory_restaurant_product_date` covers:

```text
restaurant_id, product_id, inventory_date
```

This enforces one Inventory row for the same Restaurant, Product, and date.
The service also checks:

```text
existsByRestaurantIdAndProductIdAndInventoryDate(
    Long restaurantId,
    Long productId,
    LocalDate inventoryDate)
```

The service check provides a clear domain error before insertion. The database
constraint remains necessary because concurrent requests can both pass a
pre-insert existence check.

### Optimistic locking

The `version` field uses `@Version`. JPA includes the expected version in
updates and increments it when a write succeeds. If two transactions modify
the same row from the same original version, the stale write can be rejected
instead of silently overwriting the first change.

The version is system-managed and is not exposed in the current API response.
Custom HTTP handling for optimistic-lock conflicts is not part of the current
Inventory Create implementation.

## 10. Foreign Keys and Indexes

The entity defines required join columns:

- `restaurant_id` references the owning Restaurant.
- `product_id` references the associated Product.

No `CascadeType.ALL` relationship is used. Creating or deleting Inventory
must not implicitly create or delete Restaurant or Product records.

The declared indexes are:

- `idx_inventory_restaurant_date` on
  `(restaurant_id, inventory_date)` for Restaurant/date access patterns.
- `idx_inventory_restaurant_product` on
  `(restaurant_id, product_id)` for ownership and Product-related access.
- `idx_inventory_restaurant_status` on
  `(restaurant_id, status)` for lifecycle-based Restaurant queries.

The project currently uses Hibernate `ddl-auto=update`; explicit migration
scripts are not part of this module yet. Production environments should
eventually use reviewed, version-controlled database migrations.

## 11. Create Inventory API

### Endpoint

```text
POST /api/v1/restaurants/{restaurantPublicId}/inventory
```

The controller receives the Restaurant public ID from the path and a validated
`InventoryCreateRequest` from the request body.

### Request body

```json
{
  "productPublicId": "11111111-1111-1111-1111-111111111111",
  "preparedQuantity": 50.000,
  "inventoryDate": "2026-09-28"
}
```

The client supplies only:

- Product public ID
- Prepared quantity
- Inventory date

The client cannot set available, reserved, or sold quantities, status,
Inventory public ID, version, or audit timestamps.

### Successful response

Status: `201 Created`

```json
{
  "publicId": "22222222-2222-2222-2222-222222222222",
  "restaurantPublicId": "33333333-3333-3333-3333-333333333333",
  "productPublicId": "11111111-1111-1111-1111-111111111111",
  "preparedQuantity": 50.000,
  "availableQuantity": 50.000,
  "reservedQuantity": 0,
  "soldQuantity": 0,
  "inventoryDate": "2026-09-28",
  "status": "ACTIVE",
  "createdAt": "2026-09-28T12:00:00Z",
  "updatedAt": "2026-09-28T12:00:00Z"
}
```

The response contains public identifiers and business fields. It does not
expose internal IDs, JPA entities, or the optimistic-lock version.

### Error responses

- `400 Bad Request`: request DTO validation failed.
- `404 Not Found`: the Restaurant does not exist, the Product does not exist,
  or the Product does not belong to the Restaurant.
- `409 Conflict`: Inventory already exists for the same Restaurant, Product,
  and inventory date.

Errors use the centralized `ErrorResponse` structure. The controller does not
catch domain exceptions.

## 12. Validation

`InventoryCreateRequest` applies:

- `productPublicId`: `@NotNull`.
- `preparedQuantity`: `@NotNull` and
  `@DecimalMin(value = "0.001")`.
- `inventoryDate`: `@NotNull`.

The controller activates validation with `@Valid`. A prepared quantity below
`0.001`, or a missing required field, results in `400 Bad Request`.

No additional date rule, maximum quantity, unit-of-measure rule, or
food-safety validation has been invented in the current implementation.

## 13. Service-Layer Create Flow

`InventoryServiceImpl.createInventory` performs:

1. Resolve Restaurant with
   `restaurantRepository.findByPublicId(restaurantPublicId)`.
2. Throw `RestaurantNotFoundException` when the Restaurant is absent.
3. Resolve Product with
   `findByPublicIdAndRestaurantId(productPublicId, restaurantId)`.
4. Throw `ProductNotFoundException` when the Product is absent or belongs to a
   different Restaurant.
5. Check for an existing Inventory row using Restaurant ID, Product ID, and
   inventory date.
6. Throw `InventoryAlreadyExistsException` when the business key already
   exists.
7. Create the Inventory entity and assign the validated Restaurant and Product.
8. Initialize prepared and available quantities from the request.
9. Initialize reserved and sold quantities with `BigDecimal.ZERO`.
10. Set the inventory date and initial `ACTIVE` status.
11. Save through `InventoryRepository`.
12. Map the saved entity to `InventoryResponse` while the transaction is
    active.

The entity lifecycle callback generates the Inventory public ID and audit
timestamps during persistence.

## 14. Ownership-Aware Product Lookup

Product lookup uses:

```text
findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId)
```

The service does not load a Product by public ID alone. If Product X belongs to
Restaurant A, a request that supplies Restaurant B's public ID cannot create
Inventory for Product X. The ownership-aware lookup returns no result and the
API responds with `404 Not Found`.

This pattern provides persistence-level ownership isolation. It should remain
even after authentication and authorization are introduced as defense in
depth.

## 15. Transaction Management

`createInventory` uses method-level `@Transactional`.

Restaurant lookup, Product ownership validation, duplicate detection, entity
creation, save, and response mapping form one business operation. A failure
causes the transaction to roll back.

The transaction also keeps lazy Restaurant and Product relationships available
while `InventoryResponse` is mapped. Fetch types remain `LAZY`; they were not
changed to `EAGER` merely for DTO mapping.

## 16. Layered Architecture

The request flow is:

```text
InventoryController
        ↓
InventoryService
        ↓
RestaurantRepository / ProductRepository / InventoryRepository
        ↓
JPA entities and MySQL
```

The controller handles HTTP concerns only:

- Path-variable extraction
- Request-body binding
- `@Valid` activation
- Service delegation
- `201 Created` response construction
- OpenAPI operation metadata

The service owns business orchestration, ownership checks, duplicate detection,
quantity initialization, transaction boundaries, and entity-to-response
mapping. Repositories remain responsible for persistence.

Keeping business logic out of the controller makes it reusable outside HTTP,
easier to test, and less likely to diverge across future API entry points.

## 17. Exception Handling

Centralized exception handling currently maps:

- `MethodArgumentNotValidException` to `400 Bad Request`.
- `RestaurantNotFoundException` to `404 Not Found`.
- `ProductNotFoundException` to `404 Not Found`.
- `InventoryAlreadyExistsException` to `409 Conflict`.

The Inventory controller contains no repetitive try/catch blocks. Error
responses include timestamp, numeric status, safe message, request path, and
structured validation details where applicable.

## 18. Verification Performed

The Inventory Create API has been functionally verified for:

1. Valid Inventory creation → `201 Created`.
2. Duplicate Restaurant/Product/date creation → `409 Conflict`.
3. Invalid prepared quantity → `400 Bad Request`.
4. Missing Product or Product ownership mismatch → `404 Not Found`.
5. Missing Restaurant → `404 Not Found`.

The backend also compiles successfully with the Inventory controller and its
OpenAPI annotations.

## 19. Current Limitations and Future Enhancements

Not currently implemented:

- Get, list, update, or delete Inventory APIs
- Controlled reserve, release, sale, cancellation, or adjustment operations
- Inventory status-transition rules
- Unit-of-measure modeling and conversions
- Pagination, sorting, or filtering
- Authentication or authorization
- Audit history beyond creation and update timestamps
- Explicit optimistic-lock conflict mapping
- Kafka events
- Redis caching
- AI or agent-driven Inventory changes
- Food-safety or eligibility rules
- Database migrations

Inventory is a foundation for later workflows:

```text
Inventory
    ↓
Surplus Detection
    ↓
Eligibility / Safety Rules
    ↓
Offers
    ↓
Orders
```

Those modules must be introduced separately. Surplus detection identifies
quantity and timing conditions; it does not declare food unsafe. Eligibility
and safety decisions must remain deterministic, authoritative, and independent
from AI recommendations.

Future concurrency-sensitive quantity changes should preserve the quantity
invariant, use optimistic locking, define idempotency where appropriate, and
publish events only after successful transaction completion.

## 20. Interview Concepts

Important concepts demonstrated by this module include:

- Product catalog data versus operational Inventory
- Required lazy `@ManyToOne` relationships
- Public UUID versus internal numeric ID
- BigDecimal precision for measured quantities
- Quantity invariants
- Composite unique business keys
- Ownership-aware repository methods
- Optimistic locking with `@Version`
- Method-level transaction boundaries
- DTO-controlled writable fields
- Service-layer business orchestration
- Centralized `400`, `404`, and `409` error handling
- Database constraints as protection against concurrent duplicates
- Separation of inventory lifecycle from food-safety eligibility
