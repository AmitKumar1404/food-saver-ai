# Offer Management

## 1. Module Overview

Offer Management is the marketplace boundary that turns an already eligible
surplus-food snapshot into a customer-facing discounted offer.

The upstream flow is:

```text
Restaurant
    |
    v
Product
    |
    v
Inventory
    |
    v
Surplus Detection
    |
    v
Food Eligibility Evaluation
    |
    +---- NOT_ELIGIBLE -------> Offer creation blocked
    |
    +---- REQUIRES_REVIEW ----> Offer creation blocked
    |
    +---- ELIGIBLE_FOR_OFFER -> Offer creation may proceed
    |
    v
Offer
    |
    v
Future Reservation / Order
```

An Offer is not created by Surplus Detection. It is created only after the
server validates one persisted `FoodEligibilityEvaluation` whose status is
exactly `ELIGIBLE_FOR_OFFER`.

The module follows the existing Spring Boot layered architecture:

- `controller` owns HTTP binding, status codes, and OpenAPI annotations.
- `service` and `service.impl` own business validation and transaction
  orchestration.
- `repository` owns Offer persistence checks and the pessimistic Inventory
  lock.
- `entity` represents the Offer persistence model.
- `dto.request` and `dto.response` define the API boundary.
- `exception` integrates with the existing centralized `GlobalExceptionHandler`.

### 1.1 V1 implementation status

Offer Management V1 is implemented as:

- `Offer` and `OfferStatus`;
- `OfferRepository`;
- `OfferCreateRequest` and `OfferResponse`;
- `OfferService` and transactional `OfferServiceImpl`;
- `OfferController`;
- Offer-specific exceptions integrated with `GlobalExceptionHandler`; and
- controller and MySQL-backed end-to-end tests.

The implemented V1 surface is create-only. It exposes one Restaurant-scoped
Offer creation endpoint and does not expose update, lifecycle-transition,
listing, reservation, or ordering APIs.

## 2. Business Problem

FoodSaver already knows how to:

- identify a Restaurant and its Products;
- track dated Inventory quantities;
- create immutable Surplus Detection snapshots; and
- evaluate platform eligibility through deterministic, versioned rules.

Those modules do not define a marketplace listing. A separate module is needed
to capture:

- the quantity intentionally offered to customers;
- the original and discounted monetary snapshot;
- the offer availability window;
- the Offer lifecycle;
- the exact eligibility evaluation that authorized progression; and
- marketplace history without mutating upstream audit records.

Without a separate Offer boundary, pricing, marketplace status, eligibility,
Inventory, and future reservation responsibilities would become mixed.

## 3. Purpose

Offer Management has five V1 purposes:

1. Enforce `ELIGIBLE_FOR_OFFER` as the mandatory upstream gate.
2. Validate the complete Restaurant-to-evaluation ownership chain.
3. Create an immutable pricing, quantity, and eligibility-reference snapshot.
4. Prevent duplicate or overlapping open Offers for the same Inventory.
5. Expose a stable public API without exposing internal database identifiers.

Offer creation is a marketplace/business decision. It is not a food-safety
decision.

## 4. Domain Responsibility

### 4.1 What an Offer is

An Offer is a Restaurant-owned marketplace record stating that a specific
quantity from one Inventory record is offered at a deterministic discounted
price during a defined time window, based on one persisted eligible
evaluation.

### 4.2 Responsibilities owned by Offer Management

Offer Management owns:

- Offer identity and public UUID.
- The immutable Restaurant, Product, Inventory, and eligibility references.
- Original-price, discount, final-price, and currency snapshots.
- Offered-quantity snapshot.
- Offer start and expiry timestamps.
- Offer lifecycle status.
- Duplicate and overlapping-Offer prevention.
- Offer optimistic-lock version and audit timestamps.
- Future marketplace publication lifecycle.

### 4.3 Responsibilities not owned by Offer Management

Offer Management does not own:

- Restaurant onboarding or lifecycle transitions.
- Product catalog data or base-price maintenance.
- Inventory quantity accounting.
- Surplus Detection.
- Food Eligibility rule execution or policy authoring.
- Food-safety certification.
- Customer identity, reservations, Orders, payment, or delivery.
- AI-generated pricing in V1.
- Kafka, Redis, RAG, or Agentic AI implementation.

The Offer service may validate upstream state, but it must not duplicate or
override the Food Eligibility rule engine.

## 5. Domain Relationships

### 5.1 Cardinalities

The implemented relationships are:

```text
Restaurant 1 -------- N Product
Restaurant 1 -------- N Inventory
Restaurant 1 -------- N Offer

Product    1 -------- N Inventory
Product    1 -------- N Offer

Inventory  1 -------- N SurplusDetection
Inventory  1 -------- N Offer

SurplusDetection 1 -- N FoodEligibilityEvaluation

FoodEligibilityEvaluation 1 -- 0..1 Offer
```

### 5.2 Relationship decisions

An Offer stores direct foreign keys to:

- `Restaurant`, for ownership scoping and Restaurant Offer queries;
- `Product`, for marketplace display and Product history;
- `Inventory`, for quantity consistency, concurrency, and Offer history; and
- `FoodEligibilityEvaluation`, for the exact immutable eligibility authority.

An Offer does not store a direct `surplus_detection_id`. The exact Surplus
Detection is already reachable through:

```text
Offer
  -> FoodEligibilityEvaluation
  -> SurplusDetection
```

Adding a second direct Surplus Detection foreign key would duplicate the
relationship and introduce another consistency invariant without providing a
V1 capability.

The direct Restaurant, Product, and Inventory references are deliberately
denormalized from the evaluation chain for ownership queries and future
marketplace reads. The service must prove that all direct references match the
evaluation chain before persistence. These foreign keys become immutable after
creation.

### 5.3 Multiple Offers

V1 allows:

- at most one Offer for a specific eligibility evaluation; and
- multiple historical Offers for one Inventory only when their open
  availability windows do not overlap.

The one-evaluation-to-one-Offer rule is enforced by a unique constraint on
`eligibility_evaluation_id`.

An Inventory-wide permanent unique constraint is not implemented. Such a
constraint would prevent legitimate historical re-offering after an Offer
expires or closes. Instead, creation serializes on the Inventory row and checks
for an existing open Offer whose `expires_at` is after the new creation time
using a post-lock current/locking read.

## 6. Critical Safety Boundary

The Offer module must never:

- declare food `SAFE` or `UNSAFE`;
- certify food safety;
- infer safety from surplus quantity;
- invent temperature, storage, expiry, shelf-life, or regulatory rules;
- reinterpret or override Food Eligibility rule results;
- create an Offer from `NOT_ELIGIBLE` or `REQUIRES_REVIEW`;
- permit AI or RAG to bypass deterministic controls; or
- represent `ACTIVE` Offer status as proof of food safety.

`ELIGIBLE_FOR_OFFER` is a platform workflow gate. It permits Offer creation to
be considered; it is not a safety certificate.

An Offer's `expires_at` is a marketplace availability boundary selected under
the approved Offer contract. It is not a food-safety shelf-life assertion.

## 7. V1 Scope

V1 is intentionally limited to:

- one Offer creation endpoint;
- Restaurant-scoped ownership validation;
- exact `ELIGIBLE_FOR_OFFER` enforcement;
- source-state staleness checks;
- one immutable eligibility reference;
- deterministic quantity and price validation;
- immediate activation;
- duplicate/open-window conflict prevention;
- Offer persistence with optimistic locking;
- centralized exception mapping;
- OpenAPI documentation; and
- unit and database-backed integration tests.

V1 does not include Offer update, pause, close, list, search, reservation, sale,
or expiration-job APIs. The status model is designed now so later modules do
not need to misuse eligibility or Inventory status.

## 8. Database Design

### 8.1 Implemented table

Table name:

```text
offers
```

### 8.2 Implemented fields

#### `id`

- SQL type: `BIGINT`
- Java type: `Long`
- Nullability: not null
- Constraints: primary key, auto-increment
- Purpose: internal persistence identity
- API exposure: never exposed

#### `public_id`

- SQL type: `CHAR(36)`
- Java type: `UUID`
- Nullability: not null
- Constraints: unique, immutable
- Purpose: public Offer identity used by APIs
- Generation: application-generated in `@PrePersist`

#### `restaurant_id`

- SQL type: `BIGINT`
- Java type: `Restaurant` through `@ManyToOne(fetch = LAZY)`
- Nullability: not null
- Constraints: foreign key to `restaurants.id`
- Purpose: ownership scope
- Mutability: immutable after creation

#### `product_id`

- SQL type: `BIGINT`
- Java type: `Product` through `@ManyToOne(fetch = LAZY)`
- Nullability: not null
- Constraints: foreign key to `products.id`
- Purpose: Product represented by the Offer
- Mutability: immutable after creation

#### `inventory_id`

- SQL type: `BIGINT`
- Java type: `Inventory` through `@ManyToOne(fetch = LAZY)`
- Nullability: not null
- Constraints: foreign key to `inventory.id`
- Purpose: dated Inventory source for the offered quantity
- Mutability: immutable after creation

#### `eligibility_evaluation_id`

- SQL type: `BIGINT`
- Java type: `FoodEligibilityEvaluation` through
  `@ManyToOne(fetch = LAZY)`
- Nullability: not null
- Constraints: foreign key to `food_eligibility_evaluations.id`, unique
- Purpose: exact immutable eligibility snapshot authorizing Offer creation
- Mutability: immutable after creation

The JPA cardinality remains unidirectional from Offer to evaluation. A
bidirectional collection is unnecessary.

#### `original_price`

- SQL type: `DECIMAL(12,2)`
- Java type: `BigDecimal`
- Nullability: not null
- Constraints: greater than zero
- Purpose: immutable snapshot of `Product.basePrice` at Offer creation
- Source: server-derived, never client supplied

#### `discount_percentage`

- SQL type: `DECIMAL(5,2)`
- Java type: `BigDecimal`
- Nullability: not null
- Request shape: at most three integer digits and two fractional digits
- Purpose: authoritative pricing input supplied by the Restaurant actor

`DECIMAL(5,2)` supports values up to three integer digits and two fractional
digits. The implementation does not declare a standalone configurable numeric
discount range. It rejects any discount whose calculated final price is not
positive and strictly below the original price, so free and zero-discount
Offers are not accepted by implemented V1 behavior.

#### `offer_price`

- SQL type: `DECIMAL(12,2)`
- Java type: `BigDecimal`
- Nullability: not null
- Constraints: non-negative and less than `original_price`
- Purpose: server-calculated immutable discounted price snapshot
- API behavior: returned to clients but not accepted as input

#### `currency_code`

- SQL type: `CHAR(3)`
- Java type: `String`
- Nullability: not null
- Constraints: three-letter code consistent with existing Restaurant and
  Product data
- Purpose: immutable currency snapshot
- Source: server-derived from Product after consistency validation

#### `offered_quantity`

- SQL type: `DECIMAL(12,3)`
- Java type: `BigDecimal`
- Nullability: not null
- Constraints: greater than zero; maximum nine integer digits and three
  fractional digits
- Purpose: immutable quantity made available through this Offer

This precision matches existing Inventory and eligibility quantity snapshots.

#### `start_at`

- SQL type: `TIMESTAMP(6)`
- Java type: `Instant`
- Nullability: not null
- Constraints: immutable
- Purpose: marketplace availability start
- V1 source: server-generated at successful Offer creation

Clients cannot schedule future activation in V1.

#### `expires_at`

- SQL type: `TIMESTAMP(6)`
- Java type: `Instant`
- Nullability: not null
- Constraints: immutable; must be after `start_at`
- Purpose: marketplace availability end
- Safety boundary: not a shelf-life or food-safety timestamp

#### `status`

- SQL type: `VARCHAR(32)`
- Java type: `OfferStatus`
- Nullability: not null
- Constraints: `EnumType.STRING`
- V1 creation value: `ACTIVE`
- Purpose: marketplace lifecycle only

#### `version`

- SQL type: `BIGINT`
- Java type: `Long`
- Nullability: not null
- Constraints: `@Version`
- Purpose: optimistic locking for future Offer lifecycle updates
- API exposure: not needed in the create response until an update contract is
  approved

#### `created_at`

- SQL type: `TIMESTAMP(6)`
- Java type: `Instant`
- Nullability: not null
- Constraints: immutable
- Purpose: audit timestamp generated during `@PrePersist`

#### `updated_at`

- SQL type: `TIMESTAMP(6)`
- Java type: `Instant`
- Nullability: not null
- Purpose: last lifecycle update timestamp
- Generation: initialized in `@PrePersist`, refreshed in `@PreUpdate`

### 8.3 Fields deliberately excluded from V1

The following fields are not persisted:

- `available_quantity`
- `reserved_quantity`
- `sold_quantity`

Those values belong to a future reservation/allocation model. Adding them now
would duplicate Inventory accounting without an implemented workflow that can
maintain the invariant.

Also excluded:

- `surplus_detection_id`, because evaluation already references it;
- customer or Order references, because those modules do not exist;
- AI price or confidence fields;
- safety status or certification fields;
- policy key/version duplicates, because the immutable evaluation owns them;
- title and description snapshots, because V1 can use the Product reference;
  snapshot requirements can be revisited for marketplace-history display.

## 9. Entity Relationships

The implemented Offer entity has four unidirectional lazy relationships:

```text
Offer
  |
  +-- ManyToOne Restaurant
  |
  +-- ManyToOne Product
  |
  +-- ManyToOne Inventory
  |
  +-- ManyToOne FoodEligibilityEvaluation
```

No parent entity receives an `offers` collection in V1. Repository queries can
retrieve Offers by foreign key without bidirectional collections, recursive
serialization risks, or unintended eager loading.

Creation must validate:

```text
Offer.restaurant
  == Inventory.restaurant
  == Product.restaurant
  == Evaluation.SurplusDetection.Inventory.restaurant

Offer.product
  == Inventory.product

Offer.inventory
  == Evaluation.SurplusDetection.Inventory
```

## 10. Quantity Invariants

### 10.1 V1 invariant

V1 stores only `offered_quantity`.

At creation:

```text
offered_quantity > 0

offered_quantity <= current Inventory.available_quantity

offered_quantity <=
    FoodEligibilityEvaluation.evaluated_available_quantity
```

Offer creation also requires the current Inventory version and available
quantity to match the evaluation snapshot. Therefore the two upper bounds
normally represent the same quantity, but both checks make the contract
explicit.

V1 permits offering less than the full eligible available quantity. It does
not silently offer all Inventory.

### 10.2 Rejected V1 invariant

The proposed equation:

```text
offered_quantity =
    available_quantity + reserved_quantity + sold_quantity
```

is not appropriate for Offer V1 because the Offer module has no reservation or
sale-allocation workflow. Persisting those three mutable counters now would
create duplicate accounting and unclear ownership.

A future reservation module may introduce an Offer allocation invariant such
as:

```text
offered_quantity =
    remaining_offer_quantity
    + reserved_offer_quantity
    + sold_offer_quantity
```

That decision requires reservation cancellation, expiry, oversell prevention,
and transaction semantics. It is explicitly deferred.

### 10.3 Inventory mutation

Offer creation does not decrement, reserve, close, or otherwise mutate
Inventory. `offered_quantity` is a marketplace snapshot, not an allocation.

Until reservation exists, downstream availability checks must not assume that
creating an Offer has reserved stock.

### 10.4 Quantity unit limitation

Current Inventory has no explicit unit-of-measure field. V1 therefore inherits
Inventory's implicit quantity semantics. `Offer.offeredQuantity` uses the same
implicit unit as `Inventory.availableQuantity`, and no unit conversion is
performed.

Offer Management must not invent or return a customer-facing unit such as
kilogram, litre, portion, or piece. An explicit unit-of-measure model must be
introduced before customer-facing marketplace functionality requires a
standardized quantity unit.

No unit field is added to Inventory or Offer by this design correction.

## 11. Money and Pricing Model

### 11.1 Types and precision

All monetary and percentage calculations use `BigDecimal`.

- Price precision/scale: `DECIMAL(12,2)`
- Discount precision/scale: `DECIMAL(5,2)`
- Floating-point types are prohibited.

These choices match the existing Product price precision.

### 11.2 Authoritative values

V1 authority is:

- `original_price`: copied by the server from current `Product.basePrice`;
- `discount_percentage`: accepted from the validated request;
- `offer_price`: calculated by the server; and
- `currency_code`: copied from Product after Restaurant/Product currency
  consistency validation.

The client cannot submit `original_price`, `offer_price`, or `currency_code`.

### 11.3 Formula

The implemented formula is:

```text
discount_amount =
    original_price
    * discount_percentage
    / 100

offer_price =
    original_price - discount_amount
```

The implementation uses `BigDecimal` throughout and applies
`RoundingMode.HALF_UP` once when the final `offer_price` is set to scale 2.
It does not use `double` or `float`.

### 11.4 Validation

Implemented V1 validation:

- Product base price must be positive.
- Discount must fit `DECIMAL(5,2)`.
- Calculated Offer price must fit `DECIMAL(12,2)`.
- Calculated Offer price must be positive and lower than original price.
- Product and Restaurant currency codes must match.
- Currency cannot be supplied or overridden by the client.

There is no independently configured discount-range policy in V1. The
calculated-price invariant currently excludes free, zero-discount, negative
discount, and over-100-percent outcomes without introducing category-, demand-,
or AI-based pricing.

No demand-based, category-based, time-based, or AI-generated pricing rule is
introduced.

## 12. Offer Status Lifecycle

### 12.1 Implemented statuses

#### `ACTIVE`

The Offer was successfully created, its `start_at` has been reached, and it has
not reached an authoritative terminal marketplace condition.

V1 creation produces `ACTIVE` only.

#### `EXPIRED`

The marketplace availability window ended at `expires_at`.

`EXPIRED` is a marketplace timing state, not a food-safety statement. A future
scheduled process or controlled read/update path may persist this transition.

#### `SOLD_OUT`

Future reservation/order allocation consumed the offered quantity.

The status is defined for lifecycle completeness but cannot be produced until
an authoritative allocation workflow exists.

#### `CLOSED`

An authorized Restaurant or administrative workflow deliberately ended the
Offer before ordinary completion.

The transition endpoint and authorization policy are future scope.

### 12.2 Statuses not included

`DRAFT` is not needed because V1 creates an immediately active Offer and does
not support incomplete or unpublished records.

`PAUSED` is not included because pause/resume semantics, customer visibility,
reservation handling, and authorization are not defined.

If scheduling or editing is later approved, `DRAFT`, `SCHEDULED`, or `PAUSED`
can be introduced through a deliberate migration and transition contract.

### 12.3 Future transitions

```text
ACTIVE ---- expires_at reached ----------------> EXPIRED

ACTIVE ---- future allocation reaches zero ---> SOLD_OUT

ACTIVE ---- future authorized close ----------> CLOSED
```

All terminal states remain historical. V1 does not define transitions back to
`ACTIVE`.

Only creation of `ACTIVE` is in V1 implementation scope. Expiration
persistence, close APIs, and sold-out transitions remain future work.
Regardless of stored status, `expires_at <= now` must make an Offer unavailable
to customers.

## 13. Eligibility Integration

### 13.1 Mandatory gate

Offer creation requires:

```text
FoodEligibilityEvaluation.status == ELIGIBLE_FOR_OFFER
```

Exact equality is required.

- `NOT_ELIGIBLE` blocks creation.
- `REQUIRES_REVIEW` blocks creation.
- Missing evaluation blocks creation.
- Surplus Detection status alone never authorizes creation.

The Offer module does not re-run or override eligibility rules.

### 13.2 Ownership-aware evaluation lookup

The repository lookup should scope the evaluation through:

```text
evaluation
  -> surplusDetection
  -> inventory
  -> restaurant
```

The requested `restaurantPublicId` and evaluation public UUID are resolved in
one ownership-aware lookup where practical. An evaluation owned by another
Restaurant is returned as not found.

The service derives Product and Inventory from the evaluation chain. It must
not trust independently supplied internal IDs.

V1 does not require an eligibility evaluation to be the latest evaluation for
an Inventory. The client selects a specific persisted evaluation, and Offer
creation validates that immutable evaluation against the current Inventory
state. A newer evaluation does not automatically supersede or rewrite the
selected evaluation.

### 13.3 Relationship consistency

Before creation, the service verifies:

- evaluation belongs to the requested Restaurant;
- evaluation's Surplus Detection belongs to the derived Inventory;
- Inventory belongs to the requested Restaurant;
- Inventory's Product belongs to the requested Restaurant;
- direct Offer Restaurant, Product, and Inventory references match that chain;
- Restaurant status is `ACTIVE`;
- Product status is `ACTIVE`;
- Inventory status is `ACTIVE`; and
- evaluation status is exactly `ELIGIBLE_FOR_OFFER`.

Offer Management does not re-resolve a current policy, re-run eligibility, or
compare the evaluation with a newer policy version. Persisted policy metadata
remains owned by the immutable Food Eligibility Evaluation and is not copied
into Offer.

Current lifecycle validation protects against source state changing after the
evaluation. It does not add food-safety meaning to those lifecycle statuses.

### 13.4 Staleness

V1 defines structural staleness without inventing a time-to-live:

```text
current Inventory.version
    == evaluation.evaluatedInventoryVersion

current Inventory.availableQuantity.compareTo(
    evaluation.evaluatedAvailableQuantity
) == 0
```

The comparison uses `BigDecimal.compareTo`, not `equals`, so numerically equal
values with different scales remain equal.

No arbitrary maximum evaluation age is introduced. A future authoritative
policy may define temporal applicability; Offer Management must not invent it.

If either Inventory version or available-quantity comparison fails, Offer
creation returns `409 Conflict` using the centralized `ErrorResponse`, and the
caller must request a new Surplus Detection/evaluation as appropriate.

The evaluation snapshot represents the Inventory state observed during
eligibility evaluation. V1 staleness is determined only by comparing that
selected immutable snapshot with current Inventory state. V1 introduces no
evaluation TTL, expiry, time-based staleness, or latest-evaluation lookup.

### 13.5 Eligibility immutability

The Offer stores the exact evaluation reference permanently. Later evaluations
do not rewrite an existing Offer's authorization history.

A new eligible evaluation may support a later non-overlapping Offer, but it
cannot retroactively change the first Offer.

## 14. API Design

### 14.1 Endpoint

Implemented V1 endpoint:

```http
POST /api/v1/restaurants/{restaurantPublicId}/offers
```

This follows the current Restaurant-scoped API convention. Inventory, Product,
and evaluation are derived from the selected evaluation rather than duplicated
as path parameters.

### 14.2 Request DTO

Implemented request DTO:

```text
OfferCreateRequest
```

Client-controlled fields:

- `eligibilityEvaluationPublicId` (`UUID`, required)
- `offeredQuantity` (`BigDecimal`, required)
- `discountPercentage` (`BigDecimal`, required)
- `expiresAt` (`Instant`, required)

The request must not contain:

- eligibility status;
- policy key, version, or source;
- Restaurant, Product, or Inventory internal IDs;
- original price;
- Offer price;
- currency;
- Offer status;
- Inventory version;
- evaluated facts;
- `startAt`;
- audit timestamps; or
- optimistic-lock version.

### 14.3 Response DTO

Implemented response DTO:

```text
OfferResponse
```

Response fields:

- `publicId` (`UUID`)
- `restaurantPublicId` (`UUID`)
- `productPublicId` (`UUID`)
- `inventoryPublicId` (`UUID`)
- `eligibilityEvaluationPublicId` (`UUID`)
- `originalPrice` (`BigDecimal`)
- `discountPercentage` (`BigDecimal`)
- `offerPrice` (`BigDecimal`)
- `currencyCode` (`String`)
- `offeredQuantity` (`BigDecimal`)
- `startAt` (`Instant`)
- `expiresAt` (`Instant`)
- `status` (`OfferStatus`)
- `createdAt` (`Instant`)
- `updatedAt` (`Instant`)

No internal database ID or JPA entity is exposed.

### 14.4 Successful response

```text
201 Created
```

The body is `OfferResponse`.

### 14.5 Error responses

- `400 Bad Request`: malformed path or request-body UUID, malformed JSON,
  invalid DTO shape, invalid decimal precision, DTO-level non-positive
  quantity, or invalid expiry range.
- `404 Not Found`: Restaurant or ownership-scoped eligibility chain not found.
- `409 Conflict`: evaluation is not eligible, evaluation is stale, current
  lifecycle state blocks creation, evaluation was already used, an open Offer
  overlaps for the Inventory, or optimistic/concurrent creation loses.

All errors use the existing `ErrorResponse` contract.

## 15. Validation

### 15.1 DTO validation

Implemented Bean Validation:

- `eligibilityEvaluationPublicId`: `@NotNull`
- `offeredQuantity`: `@NotNull`, minimum `0.001`, and
  `@Digits(integer = 9, fraction = 3)`
- `discountPercentage`: `@NotNull`,
  `@Digits(integer = 3, fraction = 2)`
- `expiresAt`: `@NotNull`

No client-supplied price is accepted.

`expiresAt` is compared with the one server-generated `startAt` captured by the
service. A null or non-later value raises `OfferValidationException` and maps
to `400 Bad Request`; no arbitrary minimum or maximum duration is enforced.

### 15.2 Service validation

Bean Validation cannot prove database ownership or snapshot consistency. The
service validates:

- ownership chain;
- exact eligibility status;
- Inventory version and quantity equality;
- current Restaurant, Product, and Inventory lifecycle;
- offered quantity against current/evaluated quantity;
- Product/Restaurant currency consistency;
- computed monetary capacity;
- expiry after server-generated `startAt`;
- unused eligibility evaluation; and
- no overlapping open Offer.

### 15.3 Database validation

Database nullability, foreign keys, unique constraints, decimal precision, and
selected check constraints remain the final integrity layer. They do not
replace request and service validation.

## 16. Ownership

V1 ownership validation follows the existing not-found isolation pattern:

1. Resolve the Restaurant by public UUID.
2. Resolve the eligibility evaluation by public UUID and Restaurant ownership.
3. Traverse evaluation to Surplus Detection and Inventory.
4. Verify Inventory's Restaurant matches the requested Restaurant.
5. Verify Inventory's Product belongs to the same Restaurant.
6. Derive all Offer foreign keys from the validated chain.

A caller cannot combine:

- Restaurant A;
- an evaluation from Restaurant B; or
- an Inventory/Product from another Restaurant.

Cross-Restaurant mismatches return `404`, avoiding disclosure that another
tenant's resource exists.

The implemented service performs the evaluation lookup with an
ownership-scoped JPQL join across evaluation, Surplus Detection, Inventory,
Product, and Restaurant. It selects scalar Inventory/Product IDs without
materializing an unlocked Inventory state, then obtains the current Inventory
through the repository's pessimistic-write-lock method.

Ownership validation is data isolation. Authentication and actor authorization
are separate responsibilities.

## 17. Concurrency

### 17.1 Concurrent Offer creation

Two requests may try to create Offers for the same Inventory. V1 uses this
database-backed sequence:

1. Begin the transaction.
2. Load the Restaurant through an ownership-aware lookup.
3. Load the explicitly selected immutable Food Eligibility Evaluation through
   an ownership-scoped JPQL relationship query.
4. Require evaluation status `ELIGIBLE_FOR_OFFER`.
5. Load its Inventory through an ownership-aware lookup using a pessimistic
   **WRITE** lock.
6. Validate that the evaluation belongs to the requested Restaurant and locked
   Inventory.
7. Revalidate
   `currentInventory.version == evaluation.evaluatedInventoryVersion`.
8. Revalidate
   `currentInventory.availableQuantity.compareTo(evaluation.evaluatedAvailableQuantity) == 0`.
9. Validate `offeredQuantity > 0`,
   `offeredQuantity <= currentInventory.availableQuantity`, and
   `offeredQuantity <= evaluation.evaluatedAvailableQuantity`.
10. Use a current/locking read to check that no Offer already uses the selected
    evaluation.
11. Use a current/locking read to check that no other open `ACTIVE` Offer
    exists for the Inventory.
12. Calculate pricing deterministically.
13. Persist the Offer.
14. Commit.

The Inventory lock serializes V1 Offer creation checks for the same Inventory.
After a waiting transaction acquires that lock, the duplicate and open-Offer
checks use pessimistic locking reads rather than an earlier repeatable-read
snapshot. The transaction therefore observes an Offer committed by the first
creator and returns `409 Conflict`. The unique constraint on
`eligibility_evaluation_id` remains the final database defense for two requests
using the same evaluation. Offer persistence is flushed inside the service
transaction so a violation of that specific named constraint is translated to
the same `OfferAlreadyExistsException` and `409 Conflict` contract. Other
database integrity violations are not classified as Offer conflicts. Database
constraints alone do not prevent overlapping time windows.

Offer creation does not mutate Inventory. V1 does not attempt to serialize Food
Eligibility Evaluation creation with Offer creation. Redis and distributed
locks are not part of this protocol.

### 17.2 Overlapping Offers

An **open Offer** means:

```text
status == ACTIVE
AND
expiresAt > current transaction time
```

Every V1 Offer starts immediately from a server-generated `startAt`. The
practical V1 rule is therefore that a new Offer cannot be created while another
`ACTIVE` Offer for the same Inventory remains open. The application-level
queries execute as current/locking reads while the Inventory pessimistic write
lock is held. The Inventory lock, transaction, and post-lock current reads work
together to prevent overlapping open Offers. This is an intentional V1
simplification.

An existing `ACTIVE` row whose `expiresAt <= transactionTime` does not block
creation even if a scheduled status transition has not yet changed it to
`EXPIRED`. Historical `EXPIRED`, `SOLD_OUT`, or `CLOSED` Offers likewise do not
inherently block a later Offer. The unique evaluation constraint still prevents
replaying the same eligibility evidence.

V1 does not introduce a database range or exclusion constraint because that is
not naturally supported by the current MySQL/JPA design.

### 17.3 Inventory changes

Offer creation does not increment Inventory version because it does not mutate
Inventory. It validates the version and quantity captured by the evaluation.

The transaction provides a consistent creation check, but V1 does not reserve
quantity. Inventory may legitimately change after Offer creation. Future
customer reservation must revalidate and atomically allocate quantity.

### 17.4 Offer optimistic locking

`Offer.version` protects future status or lifecycle updates from lost updates.
V1 create-only behavior initializes the version but does not expose an update
operation.

## 18. Transaction Boundary

`@Transactional` belongs on the Offer service implementation method, not on
the controller or private helpers.

The transaction includes:

- ownership-aware Restaurant and selected-evaluation loads;
- ownership-aware Inventory pessimistic write lock;
- evaluation-to-Restaurant and evaluation-to-Inventory relationship checks;
- exact eligibility status validation;
- current Inventory version and available-quantity snapshot validation;
- Restaurant, Product, and Inventory lifecycle validation;
- offered-quantity validation;
- duplicate-evaluation Offer check;
- open `ACTIVE` Offer check for the Inventory;
- deterministic pricing calculation;
- Offer persistence and flush, including translation of the named
  eligibility-evaluation unique constraint; and
- response mapping while required lazy relationships are available.

If persistence or any final validation fails, no Offer row is committed.

Offer creation is not atomic with Inventory mutation because V1 deliberately
does not mutate Inventory. Reservation and Order transactions must be designed
separately.

The controller performs no repository access and no transaction management.

## 19. Error Handling

### 19.1 Implemented exceptions

The implementation uses:

- `OfferEligibilityException`: evaluation exists and is owned correctly, but
  status, staleness, or current lifecycle blocks Offer creation; map to `409`.
- `OfferAlreadyExistsException`: evaluation already has an Offer or an open
  Offer conflicts for the Inventory; map to `409`.
- `OfferValidationException`: client-supplied expiry is not after the
  server-generated start time; map to `400`.
- `FoodEligibilityEvaluationNotFoundException`: selected evaluation is absent
  from the requested Restaurant ownership scope; map to `404` without
  disclosing cross-Restaurant existence.

`RestaurantNotFoundException` handles a missing Restaurant. The centralized
type-mismatch handler maps malformed UUID path values to `400`, while the
message-not-readable handler maps malformed JSON and malformed typed
request-body values to the same `400 ErrorResponse` contract.

`OfferNotFoundException` is not required by the create-only V1 endpoint. Add it
only when a read or lifecycle API exists.

### 19.2 Error classification

`400 Bad Request`:

- syntactically invalid request or Bean Validation failure;
- malformed UUID;
- invalid precision/scale;
- non-positive quantity;
- invalid expiry timestamp.

`404 Not Found`:

- Restaurant does not exist;
- eligibility evaluation does not exist in the Restaurant scope;
- ownership chain is inaccessible or inconsistent.

`409 Conflict`:

- evaluation status is `NOT_ELIGIBLE` or `REQUIRES_REVIEW`;
- evaluation is stale;
- current lifecycle no longer permits progression;
- discount or calculated pricing does not satisfy V1 price invariants;
- Product and Restaurant currency are inconsistent;
- eligibility evaluation was already used;
- overlapping open Offer exists;
- optimistic/pessimistic concurrent conflict.

No error should expose internal IDs, SQL, stack traces, or another
Restaurant's resource details.

## 20. Security Boundary

V1 does not implement Spring Security, authentication, roles, JWT handling, or
an authorization policy. The path carries `restaurantPublicId`, while the
request body cannot supply or override Restaurant identity.

The implemented ownership-scoped lookup prevents one Restaurant path from
using another Restaurant's evaluation chain and returns `404` without
cross-Restaurant disclosure. This data-isolation check is not a substitute for
authenticating the caller. A future security phase must define which principal
may act for the Restaurant before public production exposure, without
weakening the existing ownership checks.

## 21. Indexes and Constraints

### 21.1 Primary and unique constraints

Implemented:

- Primary key on `id`.
- Unique constraint `uk_offers_public_id` on `public_id`.
- Unique constraint `uk_offers_eligibility_evaluation` on
  `eligibility_evaluation_id`.

No permanent unique constraint exists on `inventory_id`, because it would
forbid non-overlapping historical Offers.

### 21.2 Foreign keys

Implemented mandatory relationships generate foreign keys:

- `restaurant_id -> restaurants.id`
- `product_id -> products.id`
- `inventory_id -> inventory.id`
- `eligibility_evaluation_id -> food_eligibility_evaluations.id`

Deletion behavior should preserve historical integrity. V1 should not cascade
delete upstream domain records through Offer.

### 21.3 Indexes

Implemented index:

- `idx_offers_inventory_status_expires`
  on `(inventory_id, status, expires_at)`

This index supports the V1 open-Offer conflict query. The unique eligibility
index already supports the V1 duplicate-evaluation check, and the unique public
ID index supports public identity lookup.

The following are future read/lifecycle indexes, not V1 schema requirements:

- `(restaurant_id, status, start_at)` for Restaurant Offer listings;
- `(product_id, status)` for Product marketplace reads; and
- `(status, expires_at)` for bulk expiration processing.

They should be added only with the corresponding query and execution-plan
evidence. Advanced geospatial and search indexes are also out of scope.

### 21.4 Check constraints

The entity declares `chk_offers_basic_invariants` with:

- `offered_quantity > 0`
- `original_price > 0`
- `offer_price >= 0`
- `expires_at > start_at`

The service additionally requires the calculated Offer price to be positive
and below the original price. No database check for a final numeric discount
range is declared because V1 has no independently approved/configured range.
The server validates before persistence and does not rely only on database
errors.

## 22. Swagger and OpenAPI

The implemented controller follows existing SpringDoc conventions:

- `@Operation` explains that Offer is a marketplace result gated by Food
  Eligibility and is not food-safety certification.
- `@Parameter` documents `restaurantPublicId`.
- `@ApiResponses` documents `201`, `400`, `404`, and `409`.
- `201` references `OfferResponse`.
- `400`, `404`, and `409` reference the existing `ErrorResponse`.
- The request schema contains only evaluation UUID, offered quantity, discount,
  and expiry.
- The response schema uses public UUIDs only.

The generated OpenAPI document must not imply:

- a request-supplied eligibility status;
- a request-supplied price or policy;
- Inventory mutation;
- automatic Offer creation by AI; or
- safety certification.

Controller tests should verify both runtime error bodies and generated schema
references where practical.

## 23. Auditability and Immutability

The following become immutable at creation:

- `public_id`
- Restaurant reference
- Product reference
- Inventory reference
- eligibility evaluation reference
- `original_price`
- `discount_percentage`
- `offer_price`
- `currency_code`
- `offered_quantity`
- `start_at`
- `expires_at`
- `created_at`

`status` and `updated_at` are lifecycle fields, but V1 exposes no update API.
`version` protects future updates.

The Offer retains enough information to answer:

- which Restaurant and Product published it;
- which Inventory supplied it;
- which exact eligibility evaluation authorized it;
- which price calculation was applied;
- which quantity was offered; and
- when marketplace availability began and ended.

Offer history must not rewrite eligibility, Surplus Detection, Inventory, or
Product records.

## 24. Future Kafka Integration

Kafka is not implemented in V1.

Potential future events:

- `OfferCreated`
- `OfferUpdated`
- `OfferExpired`
- `OfferSoldOut`
- `OfferClosed`

Event design requirements:

- publish only after database commit;
- use a transactional outbox or equivalent reliability mechanism;
- carry public IDs, not internal IDs;
- use versioned schemas;
- include Offer status and eligibility evaluation public reference;
- avoid sensitive policy evidence; and
- make consumers idempotent.

Events must not create eligibility or bypass synchronous creation checks.

## 25. Future Redis Usage

Redis is not implemented in V1.

Potential future uses:

- short-lived Offer availability read cache;
- marketplace list caching;
- short-lived reservation holds;
- rate limiting; and
- expiration scheduling support.

Redis must not become the authoritative source for:

- Offer history;
- eligibility status;
- Inventory quantity;
- completed reservations; or
- pricing audit data.

Reservation holds require atomic database reconciliation and expiry semantics.
They must not be added as an informal cache-only quantity model.

## 26. Future Agentic AI

Future controlled agents may:

- recommend a discount to an authorized Restaurant operator;
- rank already-active Offers for customers;
- recommend notification audiences;
- summarize Offer performance; or
- coordinate approved tools after deterministic validation.

AI must not:

- mark a Food Eligibility Evaluation as eligible;
- create an Offer from `NOT_ELIGIBLE` or `REQUIRES_REVIEW`;
- bypass ownership or staleness checks;
- change hard pricing/quantity invariants;
- certify food safety;
- mutate Inventory directly;
- create, update, close, or publish an Offer without an authorized,
  validated tool; or
- treat RAG content as a higher authority than deterministic controls.

Future Pricing Agent recommendations are proposals, not authoritative prices.
The validated Offer service remains the write boundary.

## 27. Testing Strategy

### 27.1 Current verification result

The complete Maven suite was run against the configured MySQL database after
V1 implementation:

```text
Tests run: 89
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

### 27.2 Controller and API tests

`OfferControllerTests` verifies:

- successful `201 OfferResponse` mapping and service delegation;
- DTO validation failures through the centralized `400 ErrorResponse`;
- malformed Restaurant path UUID, request-body UUID, and JSON handling;
- ownership-hidden evaluation `404`;
- eligibility, staleness, duplicate, and overlap `409` mappings;
- absence of internal IDs, version, safety, and AI metadata; and
- OpenAPI response schemas for `201`, `400`, `404`, and `409`.

### 27.3 Database-backed end-to-end tests

`OfferCreationIntegrationTests` exercises the real Spring MVC, service,
transaction, JPA, and MySQL path. It covers:

- the complete Restaurant -> Product -> Inventory -> Surplus Detection ->
  Food Eligibility Evaluation -> Offer flow;
- successful persistence with expected public IDs and server-calculated price;
- explicit selection of an older eligible evaluation even when a newer
  `NOT_ELIGIBLE` evaluation exists;
- no mutation of Inventory available, reserved, or sold quantities, status, or
  version;
- no mutation of the selected immutable eligibility evaluation;
- `NOT_ELIGIBLE` and `REQUIRES_REVIEW` rejection;
- stale Inventory version and stale available-quantity rejection using
  `BigDecimal.compareTo`;
- quantity rejection against current and evaluated availability;
- duplicate-evaluation and open-Offer conflicts;
- concurrent creation with two independent transactions, including a waiting
  creator that established a repeatable-read snapshot before the winning
  transaction committed; the test confirms the losing MySQL connection in
  `performance_schema.data_lock_waits` before releasing the winner;
- concurrent creation using the same eligibility evaluation, with exactly one
  persisted Offer and a controlled duplicate conflict for the losing request;
- focused persistence tests proving that only
  `uk_offers_eligibility_evaluation` is translated to an Offer conflict;
- real MySQL flush verification that the named evaluation constraint is
  translated inside the service transaction;
- ownership-hidden evaluation lookup;
- malformed Restaurant UUID and invalid-expiry `400` behavior; and
- reflection verification that the Inventory repository method retains
  `PESSIMISTIC_WRITE`.

Because V1 requires current and evaluated available quantities to be equal
before quantity validation, one quantity-overflow scenario exercises both
upper bounds. The test does not duplicate Food Eligibility's internal rule
matrix and does not introduce latest-evaluation semantics.

## 28. Explicitly Out of Scope

Offer V1 explicitly excludes:

- customer ordering;
- payment;
- reservation workflow;
- delivery;
- refunds or cancellation;
- customer identity;
- AI pricing;
- AI recommendation implementation;
- automatic Offer creation by AI;
- food-safety certification;
- temperature, storage, shelf-life, or regulatory rules;
- dynamic regulatory decision-making;
- Kafka implementation;
- Redis implementation;
- RAG implementation;
- Agentic AI implementation;
- advanced marketplace search;
- geospatial search;
- Offer update or pause/resume APIs;
- scheduled future activation;
- Offer/expiration scheduler implementation;
- sold-out processing;
- inventory reservation or quantity mutation; and
- a complete Spring Security implementation.

## 29. Future Roadmap

### V1: Deterministic Offer creation

- Exact eligible-evaluation gate.
- Ownership and staleness validation.
- Immediate `ACTIVE` Offer.
- Immutable quantity and price snapshot.
- Duplicate/open-window conflict prevention.
- No Inventory mutation.

### V2: Offer lifecycle and reads

- Restaurant Offer history.
- Public Offer detail.
- Authorized close operation.
- Expiration persistence.
- Pagination and basic marketplace filtering.

### V3: Reservation and Order allocation

- Customer identity.
- Short-lived reservation holds.
- Atomic quantity allocation.
- Sold-out transition.
- Cancellation and hold expiry.
- Oversell prevention.

### V4: Reliable events and caching

- Transactional outbox.
- Kafka Offer events.
- Redis read caching and controlled holds.
- Idempotent consumers.

### V5: Guarded AI assistance

- Pricing recommendations.
- Customer recommendation ranking.
- Notification coordination.
- RAG-assisted explanation from approved sources.
- Controlled tools, authorization, and audit.

AI remains advisory and cannot bypass eligibility or hard controls.

## 30. V1 Flow Diagram

```text
Client / Restaurant-scoped request
        |
        | POST /api/v1/restaurants/{restaurantPublicId}/offers
        | body:
        |   eligibilityEvaluationPublicId
        |   offeredQuantity
        |   discountPercentage
        |   expiresAt
        v
Validate UUID and request DTO
        |
        +---- invalid --------------------------> 400 ErrorResponse
        |
        v
Resolve Restaurant
        |
        +---- missing --------------------------> 404 ErrorResponse
        |
        v
Resolve eligibility evaluation through
evaluation -> detection -> inventory -> restaurant
        |
        +---- missing / wrong ownership --------> 404 ErrorResponse
        |
        v
Verify exact status == ELIGIBLE_FOR_OFFER
        |
        +---- NOT_ELIGIBLE ---------------------> 409 ErrorResponse
        |
        +---- REQUIRES_REVIEW ------------------> 409 ErrorResponse
        |
        v
Lock Inventory with pessimistic WRITE lock
        |
        v
Validate evaluation relationship chain and current lifecycle
        |
        +---- inconsistent ownership -----------> 404 ErrorResponse
        |
        +---- lifecycle no longer valid --------> 409 ErrorResponse
        |
        v
Validate selected evaluation's Inventory version
and available-quantity snapshot against current Inventory
        |
        +---- stale -----------------------------> 409 ErrorResponse
        |
        v
Validate offered quantity against both current and evaluated
availability; validate discount, currency, and expiresAt
        |
        +---- invalid expiresAt -----------------> 400 ErrorResponse
        +---- quantity/pricing/currency conflict -> 409 ErrorResponse
        |
        v
Check selected evaluation unused and no open ACTIVE Offer
for the Inventory at the transaction time
        |
        +---- duplicate / overlap --------------> 409 ErrorResponse
        |
        v
Derive original price and currency from Product
Calculate offer price deterministically
        |
        v
Persist ACTIVE Offer atomically
        |
        +---- no Inventory mutation
        +---- no reservation
        +---- no Order
        +---- no food-safety certification
        +---- no AI/RAG decision
        +---- no Redis
        +---- no Kafka
        |
        v
Return 201 Created with OfferResponse
```

## 31. Implemented V1 Decisions and Remaining Product Decisions

Implemented V1 behavior is:

- The client selects one explicit immutable
  `eligibilityEvaluationPublicId`.
- V1 intentionally does not require that evaluation to be the latest
  evaluation for the Inventory.
- The selected evaluation and current locked Inventory must have equal version
  and numerically equal available quantity.
- Creation is immediate and produces `ACTIVE`.
- `offeredQuantity` may be smaller than the evaluated/current available
  quantity.
- Only one open Offer may exist for an Inventory; non-overlapping historical
  Offers remain possible with different evaluations.
- Product and Restaurant currency must match; no FX conversion exists.
- Price uses `BigDecimal`, final scale 2, and `RoundingMode.HALF_UP`.
- The calculated Offer price must be positive and below original price, so V1
  does not accept free or zero-discount Offers.
- `expiresAt` must be after the server-generated `startAt`. V1 defines no
  minimum or maximum duration.
- Quantity inherits Inventory's implicit unit and performs no conversion.

Remaining product/security decisions for later phases are:

- whether to introduce a separately configured numeric discount policy;
- standardized customer-facing units of measure;
- persisted expiry-transition and other lifecycle workflows;
- the complete authenticated Restaurant-actor authorization contract; and
- reservation, cancellation, ordering, and allocation semantics.

`expiresAt` remains a marketplace availability boundary, not a safety or
shelf-life limit. No remaining decision may be filled by AI, RAG, or an
invented food-safety assumption.
