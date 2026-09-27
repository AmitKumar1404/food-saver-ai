# Food/Product Management

## 1. Overview

The Product Management module manages the catalog or menu items offered by a
restaurant. A `Product` describes an item such as a meal, snack, dessert,
bakery item, sweet, or beverage, together with its base price and status.

A Product is not inventory. It defines what a restaurant can offer, while a
future Inventory or stock module would track quantities, availability windows,
expiry information, and surplus units. Keeping these concepts separate avoids
mixing stable catalog data with frequently changing stock data.

The current domain relationship is Restaurant 1:N Product:

- One Restaurant can own many Products.
- Each Product must belong to exactly one Restaurant.
- The relationship is unidirectional from Product to Restaurant in the current
  entity model.

## 2. Product Data Model

The `Product` entity contains:

- `id` (`Long`): internal database primary key generated with identity strategy.
- `publicId` (`UUID`): application-generated external identifier stored as
  `CHAR(36)` and not publicly settable.
- `restaurant` (`Restaurant`): required owning restaurant, loaded lazily.
- `name` (`String`): required product name, maximum 150 characters.
- `description` (`String`): optional description, maximum 1000 characters.
- `category` (`ProductCategory`): required catalog category stored as a string.
- `basePrice` (`BigDecimal`): required positive base price with precision 12 and
  scale 2.
- `currencyCode` (`String`): required three-character currency code.
- `status` (`ProductStatus`): product status stored as a string and defaulted to
  `ACTIVE`.
- `version` (`Long`): optimistic-lock version managed by JPA.
- `createdAt` (`Instant`): system-managed creation timestamp.
- `updatedAt` (`Instant`): system-managed timestamp refreshed before updates.

The API response exposes `publicId` and `restaurantPublicId`; it does not expose
the internal Product ID, the Restaurant entity, or the version field.

## 3. Product Enums

### ProductCategory

- `MAIN_COURSE`
- `SNACK`
- `DESSERT`
- `BAKERY`
- `SWEET`
- `BEVERAGE`
- `OTHER`

### ProductStatus

- `ACTIVE`
- `INACTIVE`

## 4. Database Design

Products are persisted in the `products` table.

- Primary key: `id`.
- Unique external identifier: `public_id`, enforced by
  `uk_products_public_id`.
- Required restaurant foreign key: `restaurant_id`.
- Index `idx_products_restaurant_status` covers `(restaurant_id, status)`.
- Index `idx_products_restaurant_category` covers
  `(restaurant_id, category)`.
- The `version` column uses JPA `@Version` for optimistic locking.

The Product-to-Restaurant association is `@ManyToOne(fetch = LAZY,
optional = false)`. No cascade-all behavior or bidirectional Restaurant
collection is present.

## 5. API Endpoints

All Product endpoints are scoped below:

```text
/api/v1/restaurants/{restaurantPublicId}/products
```

### Create Product

```text
POST /api/v1/restaurants/{restaurantPublicId}/products
```

Creates a catalog item for the specified restaurant. The request body uses
`ProductCreateRequest` and is validated with `@Valid`. The service resolves the
restaurant, creates and saves the Product, and returns `ProductResponse`.

Success: `201 Created` with the created Product response.

### Get Product

```text
GET /api/v1/restaurants/{restaurantPublicId}/products/{productPublicId}
```

Retrieves one Product only when it belongs to the restaurant in the path.

- Success: `200 OK` with `ProductResponse`.
- Missing restaurant: `404 Not Found`.
- Missing Product or Product owned by another restaurant: `404 Not Found`.

### Update Product

```text
PUT /api/v1/restaurants/{restaurantPublicId}/products/{productPublicId}
```

Performs a validated full update of the client-editable Product fields:

- `name`
- `description`
- `category`
- `basePrice`
- `currencyCode`

It does not update the Product ID, public ID, owning Restaurant, status,
version, or creation timestamp. The request uses `ProductUpdateRequest` and
`@Valid`.

- Success: `200 OK` with the updated `ProductResponse`.
- Invalid request: `400 Bad Request`.
- Missing restaurant: `404 Not Found`.
- Missing Product or Product owned by another restaurant: `404 Not Found`.

### Delete Product

```text
DELETE /api/v1/restaurants/{restaurantPublicId}/products/{productPublicId}
```

Permanently deletes a Product after verifying that it belongs to the specified
restaurant.

- Success: `204 No Content`.
- The successful response has no body.
- Missing restaurant: `404 Not Found`.
- Missing Product or Product owned by another restaurant: `404 Not Found`.

## 6. Validation

`ProductCreateRequest` and `ProductUpdateRequest` currently apply the same
full-field validation:

- `name`: required, not blank, maximum 150 characters.
- `description`: optional, maximum 1000 characters.
- `category`: required.
- `basePrice`: required and greater than zero.
- `currencyCode`: required, not blank, and exactly three characters.

Validation is declared on request DTOs and triggered by `@Valid` in the
controller. The service does not duplicate these annotation-based rules.

## 7. Ownership Isolation

`ProductRepository` uses:

```text
findByPublicIdAndRestaurantId(UUID publicId, Long restaurantId)
```

The service first resolves the Restaurant by its public ID. It then queries for
the Product using both the Product public ID and the resolved Restaurant's
internal ID. This makes Restaurant ownership part of the persistence lookup
rather than relying on a Product lookup by public ID alone.

As a result, a Product belonging to Restaurant A cannot be retrieved, updated,
or deleted through Restaurant B's URL. Cross-restaurant GET, PUT, and DELETE
operations produce the same `404 Not Found` behavior as a missing Product and
do not expose the other Restaurant's internal ID.

## 8. Exception Handling

Errors are handled centrally through `GlobalExceptionHandler`:

- Request validation failure: `400 Bad Request` with field-level validation
  messages.
- Restaurant not found: `404 Not Found`.
- Product not found or Product requested through the wrong Restaurant:
  `404 Not Found`.

`RestaurantNotFoundException` and `ProductNotFoundException` are allowed to
propagate from the service. Controllers do not contain repetitive try/catch
logic.

## 9. Transaction Management

- `createProduct`: `@Transactional` because it writes a new Product.
- `getProduct`: `@Transactional(readOnly = true)` because it only reads.
- `updateProduct`: `@Transactional` because it loads and changes a Product.
- `deleteProduct`: `@Transactional` because ownership lookup and deletion form
  one write operation.

Transactions are method-level; the implementation does not use a class-level
transaction annotation.

## 10. Hard Delete vs Soft Delete

The current DELETE operation performs a hard delete by calling
`ProductRepository.delete(product)`. It does not change Product status and does
not preserve a soft-deleted row.

When future Inventory, Order, or Offer tables reference Product, deletion may
need to be revisited. Possible production strategies include restricting
deletion when references exist, deactivating Products, or implementing an
auditable soft-delete policy. None of those behaviors is implemented now.

## 11. Current Scope / Future Scope

The current Product module provides catalog-item persistence and create, get,
update, and delete APIs. It does not currently implement:

- Inventory or stock tracking
- Food-safety eligibility
- Offers or discounts
- Orders
- Authentication or authorization
- AI agents
- Kafka events
- Redis caching

These concerns are reserved for later modules and must not be inferred from the
current Product status or catalog data.

## 12. Testing Summary

The backend has been compiled successfully after each implementation step.
The functional verification checklist for the current API is:

- Create succeeds for a valid Product request.
- Create returns `400 Bad Request` for invalid request fields.
- Get returns the Product for the correct Restaurant.
- Get returns `404 Not Found` for a missing Product.
- Update succeeds and returns the updated Product.
- Update returns `400 Bad Request` for invalid request fields.
- Update returns `404 Not Found` when the Product belongs to another
  Restaurant.
- Delete succeeds with `204 No Content`.
- A subsequent get after a successful delete returns `404 Not Found`.
- Delete returns `404 Not Found` when the Product belongs to another
  Restaurant.
- A Product remains available to its owning Restaurant after a failed
  wrong-Restaurant delete.

This checklist records the expected API scenarios. No automated Product tests
have been added in the current implementation.
