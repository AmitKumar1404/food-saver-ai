# Customer Ordering

## 1. Document Status

This document records the Customer Ordering architecture, including the
original broader lifecycle target. The implemented single-Reservation Order
Management V1 contract is defined by `docs/order-management.md`, and the
implemented completion contract is defined by `docs/order-fulfillment.md`.
Where this document describes multi-item Orders or Order cancellation, those
sections remain future design only.

The completed Customer increments implement `CustomerStatus`, the `Customer`
entity, `CustomerRepository`, `CustomerCreateRequest`, `CustomerResponse`, and
the transactional Customer creation service with focused persistence,
DTO-validation, service, controller, and duplicate-constraint translation
tests. `POST /api/v1/customers`, its OpenAPI contract, and the Customer
duplicate HTTP mapping are implemented. Order and OrderItem persistence,
single-Reservation conversion, create/get/completion APIs, and their HTTP
mappings are implemented. Multi-item Orders, cancellation, migrations,
schedulers, security rules, Kafka integration, Redis integration, and AI
integration remain unimplemented. Reservation
persistence now includes `ReservationStatus`, the `Reservation` entity,
`ReservationRepository`, and focused real-MySQL persistence tests; Reservation
DTOs and the concurrency-safe allocation service are also implemented.
Reservation creation now uses pessimistic locking, authoritative Inventory and
Reservation-ledger invariants, atomic expiry release, and concurrent
idempotency recovery. The standalone expiry worker, cancellation, and read APIs
remain unimplemented.
`POST /api/v1/customers/{customerPublicId}/reservations`, its OpenAPI contract,
focused controller tests, and narrow Reservation HTTP exception mappings are
implemented.

The proposed flow extends the implemented FoodSaver domain:

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
    v
Offer
    |
    v
Customer
    |
    v
Reservation
    |
    v
Order
    |
    v
OrderItem
```

## 2. Problem Statement

Offer Management publishes a priced, time-limited marketplace snapshot, but
Offer creation deliberately does not reserve or decrement Inventory. A
Customer Ordering boundary is therefore required to:

- identify the Customer requesting an allocation;
- place a short-lived database-backed hold against one Offer;
- prevent reservations from exceeding either the Offer allocation or current
  Inventory availability;
- convert valid reservations into an Order;
- preserve immutable quantity and price snapshots in OrderItem;
- release held quantity when a reservation expires or is cancelled;
- convert reserved quantity to sold quantity when an Order is completed; and
- remain correct under retries and concurrent requests.

Customer Ordering is an allocation and marketplace workflow. It is not a
food-safety authority.

## 3. Goals

Customer Ordering V1 is designed to provide:

1. A minimal Customer profile independent of authentication credentials.
2. One-Offer Reservation holds with server-controlled expiry.
3. Orders containing one or more Reservations from one Restaurant and one
   currency.
4. Immutable OrderItem quantity and pricing snapshots.
5. Atomic Inventory quantity movement.
6. Protection against overselling under concurrent requests.
7. Idempotent Reservation and Order creation.
8. Ownership-scoped access using public UUIDs.
9. Explicit Reservation and Order lifecycle transitions.
10. Clear future integration boundaries for Kafka, Redis, and AI.

## 4. Critical Safety Boundary

Customer Ordering must preserve the following boundaries:

- Surplus does not mean unsafe food.
- Surplus does not authorize a Reservation.
- `ELIGIBLE_FOR_OFFER` is a deterministic workflow result, not food-safety
  certification.
- Offer `ACTIVE`, Reservation `ACTIVE`, and Order `CONFIRMED` are marketplace
  lifecycle states, not food-safety statuses.
- Offer expiry is a marketplace availability boundary, not a shelf-life or
  safety assertion.
- Customer Ordering consumes an existing Offer; it does not run, invent,
  weaken, or override Food Eligibility rules.
- AI, RAG, Kafka consumers, Redis, and agents cannot bypass Offer, Inventory,
  quantity, expiry, ownership, or lifecycle checks.
- Missing, inconsistent, inactive, expired, or insufficient source state must
  fail closed.

The authoritative eligibility path remains:

```text
SurplusDetection
    -> FoodEligibilityEvaluation
        -> exact status ELIGIBLE_FOR_OFFER
            -> Offer
```

Ordering never changes the persisted Food Eligibility Evaluation or its rule
results.

## 5. V1 Scope

### 5.1 Included in the design

- Customer profile and lifecycle.
- Database-backed Reservation creation and cancellation.
- Reservation expiry semantics.
- Order creation from active Reservations.
- Order completion; cancellation remains future design.
- OrderItem price and quantity snapshots.
- Inventory allocation transitions.
- Offer availability and expiry revalidation.
- Pessimistic locking and deterministic lock order.
- Idempotent create operations.
- Public UUID API contracts.
- Centralized `400`, `404`, and `409` error semantics.

### 5.2 Explicitly out of scope

- Authentication, authorization, roles, JWT, and password storage.
- Payment authorization, capture, settlement, or refunds.
- Delivery, shipping, courier, route, or address workflows.
- Taxes, fees, tips, promotions, coupons, or foreign exchange.
- Customer marketplace search and Offer recommendation APIs.
- Standardized unit-of-measure or unit conversion.
- Partial fulfillment or substitution.
- Waitlists.
- Kafka implementation.
- Redis implementation.
- Spring AI, RAG, or Agentic AI implementation.
- An implemented Reservation expiry scheduler.
- Database migration files.
- Changes to existing food eligibility rules.

## 6. Domain Responsibility

### 6.1 Customer

Customer owns:

- public marketplace identity;
- contact profile fields required by the approved API;
- Customer lifecycle status;
- optimistic-lock version; and
- audit timestamps.

Customer does not own:

- authentication credentials in V1 design;
- Restaurant data;
- Offer eligibility;
- Inventory quantities;
- payment methods;
- delivery addresses; or
- AI preference models.

### 6.2 Reservation

Reservation owns:

- the exact Customer, Restaurant, Offer, and Inventory references;
- the quantity held for one Offer;
- the Offer price and currency snapshot used for the hold;
- the hold expiry;
- the Reservation lifecycle;
- create-operation idempotency data;
- optimistic-lock version; and
- lifecycle audit timestamps.

A Reservation represents a temporary database-backed quantity allocation. It
is not an Order, payment, food-safety decision, or promise that pickup has
completed.

### 6.3 Order

Order owns:

- the Customer and fulfilling Restaurant;
- one currency for all items;
- the immutable calculated total;
- Order lifecycle status;
- create-operation idempotency data;
- optimistic-lock version; and
- lifecycle audit timestamps.

An Order may contain multiple OrderItems only when all source Reservations
belong to the same Customer, Restaurant, and currency.

### 6.4 OrderItem

OrderItem owns:

- the parent Order;
- the exact source Reservation;
- Offer, Product, and Inventory references;
- immutable Product-name and pricing snapshots;
- ordered quantity;
- immutable line total and currency; and
- creation timestamp.

OrderItem does not recalculate historical prices from mutable Product data.

## 7. Aggregate and Relationship Design

The proposed cardinalities are:

```text
Customer 1 -------- N Reservation
Customer 1 -------- N Order

Restaurant 1 ------ N Reservation
Restaurant 1 ------ N Order

Offer 1 ----------- N Reservation
Inventory 1 ------- N Reservation

Order 1 ----------- N OrderItem
Reservation 1 ----- 0..1 OrderItem

Offer 1 ----------- N OrderItem
Product 1 --------- N OrderItem
Inventory 1 ------- N OrderItem
```

The proposed entity relationship diagram is:

```text
+------------------+
| customers        |
|------------------|
| id PK            |
| public_id UK     |
| email UK         |
| status           |
| version          |
+---------+--------+
          |
          | 1
          |             N
          +---------------------------+
          |                           |
          v                           v
+--------------------+       +----------------------+
| reservations       |       | customer_orders      |
|--------------------|       |----------------------|
| id PK              |       | id PK                |
| public_id UK       |       | public_id UK         |
| customer_id FK     |       | customer_id FK       |
| restaurant_id FK  |       | restaurant_id FK     |
| offer_id FK        |       | total_amount         |
| inventory_id FK    |       | currency_code        |
| quantity           |       | status               |
| unit_price         |       | version              |
| expires_at         |       +----------+-----------+
| status             |                  |
| version            |                  | 1
+----------+---------+                  | N
           |                            v
           | 0..1             +----------------------+
           +----------------->| order_items          |
                              |----------------------|
                              | id PK                |
                              | public_id UK         |
                              | order_id FK          |
                              | reservation_id FK UK |
                              | offer_id FK          |
                              | product_id FK        |
                              | inventory_id FK      |
                              | quantity             |
                              | unit_price           |
                              | line_total           |
                              +----------------------+
```

All JPA relationships should be required, lazy, and unidirectional unless a
future query demonstrates a need for a bidirectional collection. Parent
collections are not required for V1.

No cascade from Customer Ordering may delete Customer, Restaurant, Product,
Inventory, Food Eligibility Evaluation, or Offer history.

## 8. Identifier Strategy

Every proposed table uses:

- internal `BIGINT` identity primary key for joins and persistence;
- externally visible `UUID publicId`;
- `CHAR(36)` storage, matching the existing project;
- application generation during `@PrePersist`; and
- a named unique constraint on `public_id`.

API requests and responses expose only public UUIDs. Internal IDs, foreign-key
IDs, and JPA versions are not exposed.

UUIDs do not provide authentication or authorization. They only separate the
public API contract from internal database sequences.

## 9. Customer Database Design

Implemented table:

```text
customers
```

Implemented columns:

- `id BIGINT NOT NULL AUTO_INCREMENT`: internal primary key.
- `public_id CHAR(36) NOT NULL`: immutable public UUID.
- `email VARCHAR(254) NOT NULL`: canonical lowercase email used as the V1
  business identity.
- `display_name VARCHAR(100) NOT NULL`: customer-facing display name.
- `contact_phone VARCHAR(32) NULL`: optional contact number; no V1 uniqueness
  or verification semantics are invented.
- `status VARCHAR(32) NOT NULL`: `CustomerStatus`.
- `version BIGINT NOT NULL`: optimistic-lock version.
- `created_at TIMESTAMP(6) NOT NULL`: immutable creation timestamp.
- `updated_at TIMESTAMP(6) NOT NULL`: last profile/lifecycle update.

Implemented constraints:

- Primary key on `id`.
- `uk_customers_public_id` on `public_id`.
- `uk_customers_email` on canonical `email`.
- Check that canonical email is not blank is handled by request/service
  validation; email syntax is not delegated only to a database check.

Implemented indexes:

- `idx_customers_status_created` on `(status, created_at)`.

`Customer.canonicalizeEmail` and the entity email setter apply
`Locale.ROOT` lowercase conversion. Entity lifecycle callbacks defensively
apply the same conversion before insert and update. Future services must use
the same helper before duplicate checks. V1 intentionally does not trim or
otherwise normalize email, and case-insensitive uniqueness does not depend on
database collation.

No password, password hash, role, token, consent, preference JSON, payment
method, or AI-profile column is introduced in this design.

## 10. Reservation Database Design

Implemented table:

```text
reservations
```

Implemented columns:

- `id BIGINT NOT NULL AUTO_INCREMENT`: internal primary key.
- `public_id CHAR(36) NOT NULL`: immutable public UUID.
- `customer_id BIGINT NOT NULL`: Customer foreign key.
- `restaurant_id BIGINT NOT NULL`: fulfilling Restaurant foreign key.
- `offer_id BIGINT NOT NULL`: exact Offer foreign key.
- `inventory_id BIGINT NOT NULL`: exact Inventory allocation foreign key.
- `quantity DECIMAL(12,3) NOT NULL`: held quantity.
- `unit_price DECIMAL(12,2) NOT NULL`: immutable Offer price snapshot.
- `total_amount DECIMAL(18,2) NOT NULL`: server-calculated Reservation amount.
- `currency_code CHAR(3) NOT NULL`: immutable Offer currency snapshot.
- `status VARCHAR(32) NOT NULL`: `ReservationStatus`.
- `expires_at TIMESTAMP(6) NOT NULL`: authoritative hold expiry.
- `idempotency_key VARCHAR(100) NOT NULL`: caller-supplied create key.
- `request_hash CHAR(64) NOT NULL`: SHA-256 hash of canonical business input.
- `version BIGINT NOT NULL`: optimistic-lock version.
- `created_at TIMESTAMP(6) NOT NULL`.
- `updated_at TIMESTAMP(6) NOT NULL`.
- `cancelled_at TIMESTAMP(6) NULL`.
- `expired_at TIMESTAMP(6) NULL`.
- `converted_at TIMESTAMP(6) NULL`.

Implemented constraints:

- Primary key on `id`.
- `uk_reservations_public_id` on `public_id`.
- `uk_reservations_customer_idempotency` on
  `(customer_id, idempotency_key)`.
- `quantity > 0`.
- `unit_price > 0`.
- `total_amount > 0`.
- `expires_at > created_at`.

The service remains responsible for scale-aware calculation and for validating
that `totalAmount` equals rounded `unitPrice * quantity`. A database equality
check is not proposed because decimal multiplication and application rounding
must have one explicit authority.

Implemented indexes:

- `idx_reservations_customer_status_created` on
  `(customer_id, status, created_at)`.
- `idx_reservations_restaurant_status_created` on
  `(restaurant_id, status, created_at)`.
- `idx_reservations_offer_status_expires` on
  `(offer_id, status, expires_at)`.
- `idx_reservations_inventory_status` on `(inventory_id, status)`.
- `idx_reservations_status_expires` on `(status, expires_at)` for a future
  expiry worker.

Foreign keys use restrictive deletion. Customer, Restaurant, Offer, and
Inventory records referenced by Reservation history must not be cascade
deleted.

The entity uses required lazy, unidirectional relationships and does not add
parent collections or upstream cascades. Reference, allocation snapshot,
idempotency, hash, expiry, and creation fields are immutable after insert.
Status and cancellation, expiry, and conversion timestamps remain available
for future service-controlled lifecycle transitions. This persistence
increment does not allocate Inventory, create a hold through an API, expire a
Reservation, cancel a Reservation, or convert one to an Order.

## 11. Order Database Design

`ORDER` is a SQL keyword and ambiguous domain name. The proposed table is:

```text
customer_orders
```

The Java domain name is `Order`.

Implemented columns:

- `id BIGINT NOT NULL AUTO_INCREMENT`: internal primary key.
- `public_id CHAR(36) NOT NULL`: immutable public UUID.
- `customer_id BIGINT NOT NULL`: owning Customer foreign key.
- `restaurant_id BIGINT NOT NULL`: fulfilling Restaurant foreign key.
- `total_amount DECIMAL(18,2) NOT NULL`: immutable sum of OrderItem line
  totals.
- `currency_code CHAR(3) NOT NULL`: one currency for the Order.
- `status VARCHAR(32) NOT NULL`: `OrderStatus`.
- `idempotency_key VARCHAR(100) NOT NULL`: caller-supplied create key.
- `request_hash CHAR(64) NOT NULL`: canonical request hash.
- `version BIGINT NOT NULL`: optimistic-lock version.
- `confirmed_at TIMESTAMP(6) NOT NULL`: time the Order was created.
- `completed_at TIMESTAMP(6) NULL`.
- `created_at TIMESTAMP(6) NOT NULL`.
- `updated_at TIMESTAMP(6) NOT NULL`.

Implemented constraints:

- Primary key on `id`.
- `uk_customer_orders_public_id` on `public_id`.
- `uk_customer_orders_customer_idempotency` on
  `(customer_id, idempotency_key)`.
- `total_amount > 0`.
- `CONFIRMED` requires `completed_at IS NULL`; `COMPLETED` requires
  `completed_at IS NOT NULL`.

Proposed indexes:

- `idx_customer_orders_customer_status_created` on
  `(customer_id, status, created_at)`.
- `idx_customer_orders_restaurant_status_created` on
  `(restaurant_id, status, created_at)`.

All OrderItems must have the same Customer through their Reservation, the same
Restaurant as the Order, and the same currency as the Order. These cross-table
rules are transactionally service-enforced because a normal foreign key cannot
express them.

## 12. OrderItem Database Design

Proposed table:

```text
order_items
```

Proposed columns:

- `id BIGINT NOT NULL AUTO_INCREMENT`: internal primary key.
- `public_id CHAR(36) NOT NULL`: immutable public UUID.
- `order_id BIGINT NOT NULL`: parent Order foreign key.
- `reservation_id BIGINT NOT NULL`: exact source Reservation foreign key.
- `offer_id BIGINT NOT NULL`: exact source Offer foreign key.
- `product_id BIGINT NOT NULL`: exact Product foreign key.
- `inventory_id BIGINT NOT NULL`: exact Inventory foreign key.
- `product_name VARCHAR(150) NOT NULL`: immutable display-name snapshot.
- `quantity DECIMAL(12,3) NOT NULL`: immutable ordered quantity.
- `original_price DECIMAL(12,2) NOT NULL`: Offer original-price snapshot.
- `discount_percentage DECIMAL(5,2) NOT NULL`: Offer discount snapshot.
- `unit_price DECIMAL(12,2) NOT NULL`: Offer final unit-price snapshot.
- `line_total DECIMAL(18,2) NOT NULL`: server-calculated line amount.
- `currency_code CHAR(3) NOT NULL`: immutable currency snapshot.
- `created_at TIMESTAMP(6) NOT NULL`.

OrderItem is immutable after insertion and does not require `@Version` in V1.

Proposed constraints:

- Primary key on `id`.
- `uk_order_items_public_id` on `public_id`.
- `uk_order_items_reservation` on `reservation_id`, preventing one
  Reservation from being converted into multiple Orders.
- `quantity > 0`.
- `original_price > 0`.
- `unit_price > 0`.
- `line_total > 0`.

Proposed indexes:

- `idx_order_items_order` on `(order_id)`.
- `idx_order_items_offer` on `(offer_id)`.
- `idx_order_items_inventory` on `(inventory_id)`.

The Reservation unique constraint is the final database defense against
concurrent double conversion. The service must still check the Reservation
state first to provide a clear `409 Conflict`.

## 13. Proposed Enums

### 13.1 CustomerStatus

```text
ACTIVE
SUSPENDED
CLOSED
```

- `ACTIVE`: profile may participate in Customer Ordering.
- `SUSPENDED`: new Reservations and Orders are blocked.
- `CLOSED`: profile is closed and new operations are blocked.

These are account/domain lifecycle states, not authentication or food-safety
states.

Customer creation produces `ACTIVE`. V1 Customer Ordering consumes status but
does not define public suspend, reopen, or close endpoints. Those transitions
belong to a future authenticated customer/support administration contract.
`SUSPENDED` and `CLOSED` block new Reservations and Orders but do not block
expiry, cancellation, rollback, or historical reads needed to reconcile
existing allocations.

### 13.2 ReservationStatus

`ReservationStatus` is implemented as a string-persisted enum.

```text
ACTIVE
CONVERTED
CANCELLED
EXPIRED
```

- `ACTIVE`: quantity is currently held in Inventory reserved quantity.
- `CONVERTED`: Reservation was consumed by an Order. It remains `CONVERTED`
  after completion for audit history, but only confirmed Orders contribute its
  quantity to outstanding reserved Inventory.
- `CANCELLED`: held quantity was released.
- `EXPIRED`: hold elapsed and quantity was released.

### 13.3 OrderStatus

```text
CONFIRMED
COMPLETED
```

- `CONFIRMED`: Order exists and its quantities remain reserved.
- `COMPLETED`: fulfillment completed and quantities moved from reserved to
  sold.

No payment status is encoded in `OrderStatus`.

## 14. Reservation Lifecycle

Proposed transitions:

```text
                  create succeeds
                        |
                        v
                     ACTIVE
                  /     |      \
                 /      |       \
                v       v        v
          CANCELLED  EXPIRED  CONVERTED
                                  |
                                  | linked Order cancelled
                                  v
                              CANCELLED
```

Rules:

- Creation always produces `ACTIVE`.
- `ACTIVE -> CANCELLED` releases quantity.
- `ACTIVE -> EXPIRED` releases quantity.
- `ACTIVE -> CONVERTED` occurs only inside successful Order creation.
- `CONVERTED -> CANCELLED` occurs only when the linked confirmed Order is
  cancelled and quantity is released.
- `EXPIRED` and `CANCELLED` are terminal.
- A Reservation cannot return to `ACTIVE`.
- A `CONVERTED` Reservation linked to a completed Order remains converted for
  audit history.

Reservation status has no food-safety meaning.

## 15. Order Lifecycle

Proposed transitions:

```text
Reservations converted atomically
              |
              v
          CONFIRMED
              |
              v
          COMPLETED
```

Rules:

- Creation from valid active Reservations produces `CONFIRMED`.
- `CONFIRMED -> COMPLETED` moves Inventory `reserved -> sold`.
- `COMPLETED` is terminal in V1.
- Cancellation, refunds, returns, disposal, and post-fulfillment adjustment
  policies are not implemented.
- Order lifecycle is not food-safety status.

## 16. Quantity Authority and Invariants

Inventory remains the only persisted quantity-accounting authority:

```text
preparedQuantity =
    availableQuantity
    + reservedQuantity
    + soldQuantity
```

Customer Ordering must preserve this invariant atomically.

### 16.1 Reservation creation

```text
Inventory.availableQuantity -= reservation.quantity
Inventory.reservedQuantity  += reservation.quantity
Inventory.soldQuantity       unchanged
```

### 16.2 Reservation cancellation or expiry

```text
Inventory.availableQuantity += reservation.quantity
Inventory.reservedQuantity  -= reservation.quantity
Inventory.soldQuantity       unchanged
```

### 16.3 Order creation

```text
Inventory quantities unchanged
Reservation ACTIVE -> CONVERTED
Order CONFIRMED created
OrderItems created
```

The quantity was already held by the Reservation.

### 16.4 Order completion

```text
Inventory.availableQuantity unchanged
Inventory.reservedQuantity  -= orderItem.quantity
Inventory.soldQuantity      += orderItem.quantity
Order CONFIRMED -> COMPLETED
```

### 16.5 Order cancellation

```text
Inventory.availableQuantity += orderItem.quantity
Inventory.reservedQuantity  -= orderItem.quantity
Inventory.soldQuantity       unchanged
Reservation CONVERTED -> CANCELLED
Order CONFIRMED -> CANCELLED
```

Customer Ordering V1 changes Inventory quantities but does not automatically
change `InventoryStatus`. The existing Inventory module has not yet approved
status-transition rules. Current quantities remain authoritative for
allocation, while `CLOSED` always blocks new allocation. A future Inventory
lifecycle contract may consistently derive `ACTIVE` or `DEPLETED` without
changing these quantity equations.

### 16.6 Offer allocation invariant

Offer V1 does not persist available, reserved, or sold counters. Customer
Ordering must not silently add duplicate counters without an approved Offer
schema change.

For Reservation creation, allocated quantity is derived inside the locked
transaction:

```text
allocatedOfferQuantity =
    sum(reservation.quantity)
    where reservation.offer = selectedOffer
      and reservation.status in (ACTIVE, CONVERTED)

remainingOfferQuantity =
    offer.offeredQuantity - allocatedOfferQuantity
```

The new quantity must not exceed either:

```text
remainingOfferQuantity
current Inventory.availableQuantity
```

Cancelled and expired Reservations do not consume Offer allocation.
`CONVERTED` Reservations continue to represent confirmed or completed Order
allocation unless an Order cancellation changes them to `CANCELLED`.

This design avoids a second mutable quantity ledger on Offer. If later
performance evidence requires Offer counters, they must be transactionally
reconciled with Inventory and existing Reservation/Order history.

## 17. Offer and Upstream Validation

Reservation creation must revalidate, inside the allocation transaction:

- Customer status is `ACTIVE`.
- Offer exists and is accessible by public UUID.
- Offer status is exactly `ACTIVE`.
- `startAt <= transactionTime`.
- `expiresAt > transactionTime`.
- Reservation expiry can be placed before Offer expiry.
- Restaurant, Product, Inventory, and evaluation references remain internally
  consistent.
- Restaurant, Product, and Inventory lifecycle statuses permit ordering.
- Offer still references a persisted evaluation whose status is
  `ELIGIBLE_FOR_OFFER`.
- Inventory has sufficient current available quantity.
- Offer has sufficient derived remaining quantity.
- Reservation quantity is positive.
- Offer, Product, and Restaurant currency snapshots are consistent.

Restaurant and Product lifecycle rows require current locking reads or version
revalidation before allocation is authorized. Pricing and Order snapshots use
the immutable Offer values; a concurrent change to `Product.basePrice` does not
reprice an existing Offer or Reservation.

Customer Ordering does not:

- require the Offer's evaluation to be the latest evaluation;
- re-run Food Eligibility;
- reinterpret policy metadata;
- change evaluation status;
- extend Offer expiry;
- infer a food-safety window; or
- accept an AI recommendation as authorization.

An Offer whose stored status remains `ACTIVE` but whose `expiresAt <= now` is
not reservable.

An existing valid Reservation may be converted to an Order before its own
expiry. Because Reservation expiry is capped by Offer expiry, Order creation
from an active Reservation also occurs before the marketplace Offer expires.

A confirmed Order may be completed after Offer expiry. Offer expiry blocks new
marketplace allocation; it does not retroactively invalidate a quantity that
was validly reserved and confirmed.

### 17.1 Implemented Reservation allocation service

`ReservationService.createReservation` is a non-transactional coordinator. It
validates structural input and computes the canonical request hash before
delegating to a separately proxied allocation command using
`READ_COMMITTED`. The command locks Customer, Restaurant, Product, Inventory,
all referenced Offers, and all active Reservations in canonical order.

While Inventory is write-locked, creation requires both:

```text
prepared = available + reserved + sold
reserved =
    SUM(ACTIVE Reservation quantity)
    + SUM(CONVERTED Reservation quantity linked to CONFIRMED Orders)
```

Converted Reservations linked to completed Orders are excluded from
outstanding reserved quantity, while orphan converted Reservations fail
closed. Expired active holds are released atomically before Offer allocation is
recalculated. Successful creation moves Inventory quantity from available to
reserved, persists the new active Reservation with `saveAndFlush`, and verifies
both invariants again. Pricing, currency, and expiry remain server-derived.
Any later failure rolls back expiry changes, Inventory movement, and the new
Reservation together.

### 17.2 Allocation feature gate and legacy reconciliation

Allocation is disabled by default:

```properties
foodsaver.ordering.allocation-enabled=false
```

Tests enable it explicitly. Development data must be reset before enabling:
remove foundation-era Reservation rows, reset affected Inventory fixtures, and
verify the Inventory equation with `reservedQuantity = 0`. Test setup records
an explicit `RESERVATION_ALLOCATION_V1` reconciliation marker; the production
default is never weakened for development or tests.

The durable `ordering_reconciliation_markers` row is keyed by the exact
allocation release identifier. Its state moves from `RECONCILED_BASELINE` to
`ACTIVATED` only after the `REPEATABLE_READ` startup preflight confirms there
are no legacy rows requiring classification, every Inventory and the
completion-aware Reservation ledger reconcile, and there are no orphan
`CONVERTED` Reservations. The marker transition and preflight commit before an
in-process activation guard permits ordering traffic. Missing, wrong-release,
or invalid markers fail startup; requests also fail closed while preflight is
incomplete. On later restarts, the activated release marker is still required
and current Inventory and completion-aware ledger invariants are revalidated.
Preflight uses its own non-locking `REPEATABLE_READ` snapshot and one grouped
Reservation-ledger query, so Inventory values and Reservation sums come from
one committed snapshot without globally blocking allocation writes. Runtime
allocation remains `READ_COMMITTED`.

Production must not enable the flag until Reservation writes are quiesced,
data is backed up, every foundation-era active Reservation is explicitly
classified without changing Inventory, no converted Reservation remains, all
Inventory and ledger checks pass, and a durable release-specific
reconciliation marker is recorded. The marker schema and validation are
implemented, but no production reconciliation has been performed and no
automatic quantity-based legacy matching exists. The operational process,
cutoff, migration/deployment sequencing, and marker insertion remain production
deployment tasks. Per-allocation invariant checks remain a runtime fail-closed
defense; they do not replace that reconciliation.

Reservation cancellation remains deferred because only Reservations created
by the allocation transaction are guaranteed to own Inventory reserved
quantity.

## 18. Reservation Expiry Strategy

The Reservation expiry is server-generated:

```text
reservation.expiresAt =
    min(transactionTime + configuredReservationTtl, offer.expiresAt)
```

V1 uses one global Reservation TTL configured through:

```properties
foodsaver.ordering.reservation-ttl
```

The development default is `PT15M`, configurable through `RESERVATION_TTL`.
The service reads the property as `Duration`; it does not hard-code the hold
duration. V1 does not vary the TTL by Restaurant, Offer, Product, or Customer.

The configured TTL is an operational marketplace hold duration. It is not a
food-safety limit.

If the calculated expiry is not after `transactionTime`, Reservation creation
is rejected because no valid hold window remains.

V1 design uses the database as the authority:

- Every command treats `ACTIVE` with `expiresAt <= now` as expired even if the
  stored status has not yet been updated.
- Before calculating allocation for an Offer, all expired active Reservations
  for the locked Inventory must be released in the same controlled
  transaction, including holds from earlier Offers. The related Offer rows are
  locked in ascending ID order before Reservation rows.
- A production implementation also requires an authoritative expiry processor
  so holds are eventually released even when no later request touches the same
  Inventory. That processor may scan
  `status = ACTIVE AND expires_at <= now` in bounded batches.
- The worker must lock the relevant Inventory, Offer, and Reservation rows,
  re-check state, release quantity once, and mark `EXPIRED`.
- Repeated expiry attempts are idempotent because only `ACTIVE` can transition
  to `EXPIRED`.

Redis may later accelerate timers or wake-up notifications, but Redis expiry
must never be the only action that releases durable Inventory.

If cancellation races with expiry, the transaction that acquires the canonical
locks first re-checks the timestamp. When `expiresAt <= transactionTime`,
expiry wins: quantity is released once and status becomes `EXPIRED`. A later
cancel request observes the terminal state and must not release quantity again.

Expiry has precedence over `SOLD_OUT` for marketplace lifecycle. Completion
after Offer expiry may still move valid confirmed allocation from reserved to
sold, but completion never changes Offer status. A future Offer lifecycle
processor may persist `EXPIRED`.

## 19. Pricing and Currency

Reservation and Order pricing is derived from the immutable Offer:

```text
reservation.unitPrice = offer.offerPrice

reservation.totalAmount =
    round(reservation.quantity * reservation.unitPrice, 2, HALF_UP)
```

OrderItem snapshots:

- Product name;
- Offer original price;
- Offer discount percentage;
- Offer final unit price;
- ordered quantity;
- currency; and
- rounded line total.

Order total is calculated server-side:

```text
order.totalAmount =
    sum(orderItem.lineTotal)
```

Rules:

- Use `BigDecimal`; never `float` or `double`.
- Compare numeric quantities using `compareTo`.
- Apply `HALF_UP` when a monetary total is rounded to scale 2.
- Do not recalculate `unitPrice` from current `Product.basePrice`.
- Do not accept unit price, line total, total amount, or currency from the
  client.
- All items in one Order must use the same currency.
- All Reservations in one Order must belong to one Restaurant.
- No foreign-exchange conversion is attempted.
- Free Offers remain unsupported because Offer V1 requires a positive final
  price.

The current domain has no unit-of-measure field. Reservation and OrderItem
quantity inherit the exact implicit unit used by Offer and Inventory. APIs must
not label that value as kilograms, litres, pieces, or portions until a shared
unit model is approved.

## 20. Transaction Boundaries

### 20.1 Create Reservation transaction

The implemented non-transactional coordinator first checks the completed
startup activation guard, validates structural input, and computes the request
hash. A separately proxied command then performs the following in one
`READ_COMMITTED` transaction:

1. Resolve Customer and validate idempotency replay.
2. Resolve Offer and derive Restaurant, Product, and Inventory IDs without
   trusting client-supplied internal IDs.
3. Acquire locks in the canonical order.
4. Re-read and validate current Customer, Inventory, Offer, and lifecycle
   state.
5. Release expired active Reservations for the locked Inventory, including
   Reservations created from earlier Offers, after locking their Offer rows in
   ascending ID order.
6. Calculate allocated and remaining Offer quantity.
7. Validate current Inventory available quantity.
8. Calculate Reservation price snapshots and expiry.
9. Move Inventory `available -> reserved`.
10. Persist Reservation.
11. Flush named unique/idempotency constraints inside the transaction.
12. Map the response while lazy relationships are available.
13. Commit.

Any failure rolls back both Reservation persistence and Inventory mutation.
The authoritative transaction time is captured only after every canonical lock
is acquired and is normalized once to MySQL `TIMESTAMP(6)` microsecond
precision. It supplies both `createdAt` and the
`min(transactionTime + TTL, offer.expiresAt)` expiry; a window that is not
strictly positive after the same microsecond normalization is rejected before
expiry release or Inventory mutation. Reservation lifecycle callbacks and
timestamps use the same microsecond normalization, so immediate responses and
reloaded database values remain identical.

Same-key/same-hash `ACTIVE` replays enter the same lock sequence. If the hold is
logically expired, that transaction validates the ledger and releases it
exactly once before returning `EXPIRED`; it never returns a stale `ACTIVE`
representation. A named unique-constraint loser exits and rolls back first,
verifies the winner in a separate read-only transaction, and then re-enters the
canonical command so the same logical-expiry rule applies.

### 20.2 Cancel Reservation transaction

One transaction must:

1. Resolve the ownership-scoped Reservation.
2. Lock Customer, Inventory, Offer, and Reservation in canonical order.
3. Re-check that status is `ACTIVE`.
4. Move Inventory `reserved -> available`.
5. Mark Reservation `CANCELLED`.
6. Set `cancelledAt`.
7. Commit atomically.

Repeated cancellation may return the existing cancelled result without moving
quantity again. Cancelling `CONVERTED` directly is forbidden; the linked Order
cancellation workflow owns that release.

### 20.3 Create Order transaction

One transaction must:

1. Resolve Customer and idempotency replay.
2. Resolve all requested Reservations by public UUID and Customer ownership.
3. Reject an empty or duplicate Reservation list.
4. Require all Reservations to belong to one Restaurant and currency.
5. Acquire all required locks in deterministic order.
6. Re-read each Reservation and require `ACTIVE` and unexpired.
7. Verify each Reservation still matches its Offer and Inventory.
8. Create one immutable OrderItem per Reservation.
9. Calculate the Order total from server-calculated line totals.
10. Mark Reservations `CONVERTED`.
11. Persist and flush Order and OrderItems.
12. Commit.

Inventory quantity is not changed because it is already reserved.

### 20.4 Complete Order transaction

The implemented customer-owned endpoint delegates to one separately proxied
`READ_COMMITTED` transaction:

1. Lock Customer `PESSIMISTIC_WRITE`.
2. Resolve immutable target IDs without locking.
3. Lock Order `PESSIMISTIC_WRITE`.
4. Return an already completed Order unchanged after persisted response
   relationship validation.
5. Lock Restaurant and Product `PESSIMISTIC_READ`.
6. Lock Inventory `PESSIMISTIC_WRITE`.
7. Lock Offers and Reservations `PESSIMISTIC_WRITE`, each in ascending
   internal-ID order.
8. Capture one server-generated microsecond-normalized timestamp.
9. Revalidate all persisted relationships, snapshots, lifecycle state,
   Inventory equation, completion-aware ledger, and absence of orphan
   converted Reservations.
10. Move Inventory `reserved -> sold`, preserving prepared, available, and
    status.
11. Mark Order `COMPLETED`, setting `completedAt == updatedAt`.
12. Flush and revalidate deltas, persistence, and both invariants.
13. Commit atomically.

Reservation remains `CONVERTED`. Completion does not reprice the Order or
change Offer or Inventory status.

### 20.5 Cancel Order transaction

Order cancellation is not implemented. A future design would need one
transaction that:

1. Resolve ownership-scoped Order.
2. Acquire the same deterministic locks.
3. Require Order status `CONFIRMED`.
4. Move each Inventory `reserved -> available`.
5. Mark source Reservations `CANCELLED`.
6. Mark Order `CANCELLED` and set `cancelledAt`.
7. Commit atomically.

No partial item completion is allowed in V1.

Expiry and rollback reconciliation must remain possible even
when Customer, Restaurant, Product, Inventory, or Offer lifecycle state later
becomes inactive. Current lifecycle checks block new allocation; they must not
trap quantity in `reservedQuantity`. Completion of an already confirmed Order
is authorized by the narrow fulfillment accounting contract and never bypasses
quantity reconciliation.

Any future cancellation design must define its cutoff and concurrency behavior
against the atomic `CONFIRMED -> COMPLETED` transition before implementation.

The implemented completion endpoint is Customer-scoped by public UUID. It must
not be exposed to an untrusted production network until a future security
contract authenticates and authorizes the actor.

## 21. Locking Strategy

### 21.1 Canonical lock order

Order completion uses this canonical lock order:

```text
Customer
    -> Order
        -> Restaurant
            -> Product
                -> Inventory
                    -> Offers ordered by internal ID
                        -> Reservations ordered by internal ID
```

Allocation and conversion have no pre-existing Order to lock, so they retain
their compatible prefix:

```text
Customer -> Restaurant -> Product -> Inventory -> Offers -> Reservations
```

All workflows lock Inventory before Offer and Reservation. Completion locks its
Order immediately after Customer, before entering that shared suffix; no
workflow acquires Order after any suffix lock.

Customer locking may be used as a current lifecycle read and per-Customer
serialization point. It does not replace authentication.

Restaurant and Product locking/current version checks stabilize lifecycle and
currency validation. Their IDs must be sorted for multi-item Orders before
Inventory locks are acquired. Existing Offer creation does not acquire
Restaurant or Product write locks, so it does not introduce a reverse
Offer-to-Product lock order.

### 21.2 Pessimistic locking

Use `PESSIMISTIC_WRITE` for:

- Customer rows that serialize Customer-scoped idempotency;
- Order rows undergoing completion;
- Inventory rows whose quantities will change;
- Offer rows whose allocation capacity is being checked;
- Reservation rows undergoing transition or reconciliation.

Reservation allocation uses the canonical order:

```text
Customer PESSIMISTIC_WRITE
    -> Restaurant PESSIMISTIC_READ
    -> Product PESSIMISTIC_READ
    -> Inventory PESSIMISTIC_WRITE
    -> Offers PESSIMISTIC_WRITE ordered by internal ID
    -> ACTIVE Reservations PESSIMISTIC_WRITE ordered by internal ID
```

The allocation transaction uses `READ_COMMITTED`, not MySQL's default
`REPEATABLE_READ`, so aggregate queries executed after an Inventory lock wait
see the latest committed Reservation ledger. The Inventory row is the
serialization point; aggregate `SUM` queries validate state and are not used as
a substitute for locking.

The final availability and lifecycle checks must execute after locks are
acquired. Pre-lock checks may provide early rejection but cannot authorize the
write.

### 21.3 Optimistic locking

Use `@Version` on Customer, Reservation, and Order, consistent with existing
mutable aggregates.

Pessimistic locking serializes short allocation transactions. Optimistic
locking protects future profile and lifecycle updates that do not otherwise
hold a database lock. They solve different problems and may coexist.

### 21.4 Operational risks

Pessimistic locking introduces:

- lock waits;
- timeout risk;
- deadlock risk if lock order is violated;
- reduced throughput for heavily requested Offers; and
- longer contention when transactions perform unnecessary work while locked.

Transactions must remain short. External calls, AI calls, notifications,
Kafka publication, and Redis operations must not occur while database locks are
held.

## 22. Concurrent Ordering and Overselling Prevention

Example: two Customers reserve the last `2.000` units.

```text
Inventory.availableQuantity = 2.000
Offer.remainingQuantity     = 2.000

Transaction A                         Transaction B
-------------                         -------------
begin                                 begin
lock Inventory                        wait for Inventory lock
lock Offer
re-check current quantities
reserve 2.000
available 2.000 -> 0.000
reserved 0.000 -> 2.000
insert Reservation A
commit
                                      acquire Inventory lock
                                      acquire Offer lock/current read
                                      re-check current quantities
                                      available is 0.000
                                      reject with 409
                                      rollback
```

Overselling is prevented by the combination of:

- Inventory row serialization;
- Offer row serialization;
- post-lock current-state checks;
- derived Offer allocation calculation;
- atomic Inventory mutation;
- Reservation and OrderItem unique constraints;
- optimistic versions for later updates; and
- transaction rollback.

Application existence checks are not substitutes for database constraints.
Database constraints are not substitutes for locked aggregate-quantity checks.

## 23. Idempotency Strategy

Reservation and Order creation are retryable mutating operations and require an
`Idempotency-Key` request header.

### 23.1 Scope

Keys are scoped to Customer:

```text
(customer_id, idempotency_key)
```

Reservation and Order use separate tables, so the same textual key may be used
once in each operation type without collision.

### 23.2 Canonical request hash

The server calculates a SHA-256 hash over canonical business input:

Reservation:

```text
customerPublicId
offerPublicId
quantity normalized to database scale
```

Order:

```text
customerPublicId
sorted reservationPublicIds
```

Headers, timestamps generated by the server, and presentation-only JSON
ordering are excluded.

### 23.3 Replay behavior

- Same key and same hash: return the existing resource without repeating
  quantity mutation.
- Same key and different hash: return `409 Conflict`.
- New key: execute normally.
- Concurrent same-key requests: application pre-check plus named database
  unique constraint.

The implemented coordinator validates a non-blank key of at most 100
characters, normalizes quantity to scale 3, and persists the lowercase
64-character SHA-256 hash of the three newline-separated canonical values.
The Customer write lock serializes same-Customer requests. Same-key/same-hash
calls return the existing Reservation, while a different hash raises
`ReservationIdempotencyConflictException`.

The unique database constraint remains the final invariant. Only the named
`uk_reservations_customer_idempotency` constraint is translated. Its loser
exits and rolls back the allocation transaction before a separate
`REQUIRES_NEW`, read-only replay transaction reads and verifies the winner.
Unrelated integrity violations propagate unchanged.

The persistence operation must flush inside the service transaction when
constraint-specific translation is required.

A unique-constraint failure marks the write transaction rollback-only. The
service must not catch that failure and query for the winning row in the same
transaction. The proposed call structure is:

```text
Controller
    -> non-transactional idempotency coordinator
        -> transactional Reservation/Order command
            -> flush
            -> named unique constraint loses
            -> exception leaves boundary and transaction rolls back
        -> separate read-only replay lookup
            -> same hash: return winner
            -> different hash: return 409
```

This recovery path uses a new transaction only after the failed transaction has
fully rolled back. It does not continue work in a rollback-only persistence
context.

Idempotency does not permit replaying an expired business authorization. It
prevents duplicate side effects for the original operation.

## 24. Rollback Behavior

The following must be one atomic database transaction:

- Reservation row plus `available -> reserved`.
- Reservation cancellation/expiry plus `reserved -> available`.
- Order plus all OrderItems plus Reservation conversion.
- Order completion plus all `reserved -> sold` movements.
- Order cancellation plus all `reserved -> available` movements.

If any item fails:

- no partial Order is committed;
- no partial Inventory movement is committed;
- no Reservation is partially transitioned;
- Offer status is not partially changed; and
- no event is published.

Known named unique-constraint violations may be translated to domain
exceptions only after an explicit flush. The translated runtime exception must
leave the transactional boundary so Spring rolls back; code must not catch the
exception and continue using a rollback-only transaction.

## 25. Ownership and Data Isolation

### 25.1 Customer scope

- Customer APIs resolve Customer by public UUID.
- Reservations are looked up by both Reservation public UUID and Customer ID.
- Orders are looked up by both Order public UUID and Customer ID.
- Another Customer's Reservation or Order returns `404 Not Found`.

### 25.2 Restaurant scope

- Restaurant fulfillment APIs resolve Orders by both Order public UUID and
  Restaurant ID.
- A Restaurant cannot complete or inspect another Restaurant's Order.
- Order creation verifies every Reservation belongs to the same Restaurant.

### 25.3 Relationship consistency

The service derives Restaurant, Product, and Inventory through the locked
Offer/Reservation chain. Clients cannot supply internal IDs or independently
combine resources from different Restaurants.

Cross-scope mismatches return `404` where revealing existence would leak
another Customer's or Restaurant's resource.

Ownership/data isolation is not authentication. This design does not define
which authenticated principal represents the Customer or Restaurant.

## 26. API Design Overview

The proposed API uses public UUIDs only.

### 26.1 Customer

Implemented:

```http
POST /api/v1/customers
GET  /api/v1/customers/{customerPublicId}
```

Create request fields:

- `email`
- `displayName`
- optional `contactPhone`

The implemented `CustomerCreateRequest` validation contract is:

- `email`: required, non-blank, valid email format, and at most 254
  characters;
- `displayName`: required, non-blank, and at most 100 characters; and
- `contactPhone`: optional and at most 32 characters, with no invented
  country-specific format rule.

The DTO preserves the submitted email value. Canonical lowercase conversion
remains a service/domain persistence responsibility.

The implemented `CustomerResponse` exposes:

- `publicId`;
- `email`;
- `displayName`;
- `contactPhone`;
- `status`;
- `createdAt`; and
- `updatedAt`.

It does not expose the internal database ID or optimistic-lock version.

The implemented `CustomerService.createCustomer` operation:

1. canonicalizes the validated request email through
   `Customer.canonicalizeEmail`, which applies `Locale.ROOT` lowercase only;
2. uses `existsByEmail` for fast duplicate feedback;
3. creates a server-controlled `ACTIVE` Customer;
4. persists with `saveAndFlush` inside one `@Transactional` boundary; and
5. maps only the approved `CustomerResponse` fields.

The `uk_customers_email` unique constraint remains the final authority during
concurrent creation. Only a Hibernate `ConstraintViolationException` naming
that constraint is translated from `DataIntegrityViolationException` to
`CustomerAlreadyExistsException`; unrelated integrity failures are propagated.
The exception leaves the transactional method, so the failed transaction is
rolled back rather than continued after a failed flush.

`CustomerController` applies `@Valid` at the HTTP boundary and delegates
directly to `CustomerService`; it does not canonicalize email, query a
repository, or perform duplicate checks. Successful creation returns
`201 Created`.

Customer creation uses the existing `ErrorResponse` contract:

- `400 Bad Request` for Bean Validation failures or malformed JSON;
- `409 Conflict` when `CustomerAlreadyExistsException` reports an existing
  canonical email.

The OpenAPI operation documents `CustomerResponse` for `201` and
`ErrorResponse` for `400` and `409`. Internal ID and version are absent from
both Customer DTO schemas.

`GET /api/v1/customers/{customerPublicId}` retrieves one Customer by external
public UUID. The controller accepts `customerPublicId` as `UUID` and delegates
to the read-only transactional service, which queries
`CustomerRepository.findByPublicId`; internal database IDs are not accepted or
exposed.

The GET endpoint returns:

- `200 OK` with the approved `CustomerResponse` fields when found;
- `400 Bad Request` with the existing invalid-path-parameter `ErrorResponse`
  when `customerPublicId` is not a valid UUID; and
- `404 Not Found` with `ErrorResponse` when `CustomerNotFoundException`
  reports no Customer for the public UUID.

Its OpenAPI operation documents `CustomerResponse` for `200` and
`ErrorResponse` for `400` and `404`. Customer update, delete, list, and search
APIs remain unimplemented.

### 26.2 Reservation

The Reservation creation endpoint is implemented:

```http
POST /api/v1/customers/{customerPublicId}/reservations
```

The following paths remain unimplemented future API design:

```http
POST /api/v1/customers/{customerPublicId}/reservations/{reservationPublicId}/cancel
GET  /api/v1/customers/{customerPublicId}/reservations/{reservationPublicId}
```

Create headers:

- required, non-blank `Idempotency-Key` with at most 100 characters

Create request fields:

- `offerPublicId`
- `quantity`

`ReservationResponse` exposes `publicId`, the Customer, Restaurant, Offer, and
Inventory public UUIDs, quantity, unit price, total amount, currency, status,
expiry, and creation/update timestamps. It excludes internal IDs, optimistic
version, idempotency key, and request hash.

The client cannot submit:

- Restaurant, Product, Inventory, or evaluation IDs;
- price, currency, total, status, or expiry;
- Inventory quantities; or
- food eligibility status.

The controller accepts `customerPublicId` as a UUID, applies Bean Validation to
`ReservationCreateRequest`, validates only the HTTP header shape, and delegates
the exact UUID, DTO, and unmodified idempotency key to `ReservationService`.
Successful creation returns `201 Created` and `ReservationResponse`.

HTTP behavior:

- `400 Bad Request`: malformed Customer UUID or JSON, DTO validation failure,
  or missing, blank, or overlength `Idempotency-Key`;
- `404 Not Found`: Customer or Offer does not exist; and
- `409 Conflict`: Reservation lifecycle/business validation or idempotency
  payload conflict.

All failures use the existing `ErrorResponse`.
`ReservationAllocationConflictException` maps narrowly to `409 Conflict`
without exposing internal IDs, quantities, lock diagnostics, or SQL details.
Hash calculation, replay decisions, pricing, expiry, source validation,
locking, allocation, and persistence remain service responsibilities.

Creation now atomically moves Inventory from available to reserved, derives
Offer allocation from active and converted Reservations, prevents concurrent
overselling, and releases expired active holds encountered for the locked
Inventory. It does not implement Reservation cancellation, a standalone expiry
worker, or Reservation reads.
`ELIGIBLE_FOR_OFFER` remains an upstream workflow result and is not food-safety
certification.

### 26.3 Order

The single-Reservation V1 create, customer-owned read, and completion endpoints are
implemented:

```http
POST /api/v1/customers/{customerPublicId}/orders
GET  /api/v1/customers/{customerPublicId}/orders/{orderPublicId}
POST /api/v1/customers/{customerPublicId}/orders/{orderPublicId}/complete
```

Cancellation remains future design:

```http
POST /api/v1/customers/{customerPublicId}/orders/{orderPublicId}/cancel
```

Create headers:

- required `Idempotency-Key`

Create request fields:

- required `reservationPublicId`

The response contains:

- Order public UUID;
- Customer and Restaurant public UUIDs;
- status;
- total and currency;
- lifecycle timestamps; and
- OrderItems containing only public UUIDs and immutable snapshots.

New creates and successful same-request idempotent creation replays return
`201 Created`. Completion takes no body, timestamp, or idempotency key and
returns `200 OK`; a completed replay returns the original persisted completed
representation without moving Inventory again. `docs/order-management.md` and
`docs/order-fulfillment.md` are the authoritative contracts.

## 27. Error Handling

All errors use the existing `ErrorResponse`.

### 27.1 `400 Bad Request`

- Bean Validation failure.
- Malformed UUID, timestamp, number, or JSON.
- Missing `Idempotency-Key`.
- Empty Reservation list.
- Duplicate Reservation UUIDs in one Order request.
- Non-positive or unsupported-scale quantity.
- Structurally invalid request.

### 27.2 `404 Not Found`

- Customer does not exist.
- Offer does not exist.
- Reservation does not exist in the Customer scope.
- Order does not exist in the Customer or Restaurant scope.
- Relationship/ownership mismatch hidden from the caller.

### 27.3 `409 Conflict`

- A Customer canonical email already exists.
- Customer is suspended or closed.
- Offer is inactive, closed, sold out, or expired.
- Restaurant, Product, or Inventory lifecycle blocks allocation.
- Insufficient Inventory available quantity.
- Insufficient derived Offer remaining quantity.
- Reservation is expired, cancelled, or already converted.
- Order lifecycle transition is invalid.
- Reservations belong to different Restaurants or currencies.
- Idempotency key is reused with different input.
- Reservation is concurrently converted by another request.
- Optimistic-lock, lock-timeout, or known allocation conflict.
- A named database constraint representing one of the above loses a race.

Unrelated `DataIntegrityViolationException` values must not be globally mapped
to `409`. SQL, constraint names, stack traces, and internal IDs must not be
exposed.

## 28. Entity Mapping Decisions

Proposed JPA conventions:

- `@Entity` and explicit plural table names.
- `Long` identity primary keys.
- `UUID` public IDs with `@JdbcTypeCode(SqlTypes.CHAR)`.
- `EnumType.STRING`.
- Required lazy `@ManyToOne` relationships.
- `updatable = false` for immutable references and snapshots.
- `@Version` on Customer, Reservation, and Order.
- `@PrePersist` for public UUID and audit initialization.
- `@PreUpdate` for mutable aggregate timestamps.
- No `CascadeType.ALL` to upstream entities.
- No direct entity serialization from controllers.

OrderItem may be persisted explicitly by its repository or through a narrowly
scoped Order-to-items persistence cascade. Cascade delete is not appropriate
for durable Order history.

## 29. Future Kafka Integration

Kafka is not implemented by V1.

Potential future events:

```text
CustomerCreated
ReservationCreated
ReservationCancelled
ReservationExpired
OrderCreated
OrderCompleted
OrderCancelled
InventoryAllocationChanged
OfferSoldOut
```

Requirements:

- Events are emitted only after database commit.
- Use a transactional outbox or equivalent reliable publication strategy.
- Event IDs and consumer operations are idempotent.
- Schemas are versioned.
- Payloads use public UUIDs, not internal IDs.
- Customer PII is minimized.
- Consumers cannot change authoritative quantity or eligibility by replaying
  an event.
- Delayed events cannot reactivate an expired Reservation or Offer.

Kafka must not participate in the synchronous overselling decision.

## 30. Future Redis Integration

Redis is not implemented by V1.

Potential uses:

- idempotency response cache backed by durable database records;
- Reservation expiry wake-up scheduling;
- rate limiting;
- short-lived Offer availability read cache; and
- customer-facing marketplace read optimization.

Redis must not be the source of truth for:

- Inventory available, reserved, or sold quantities;
- Offer eligibility or status history;
- durable Reservation state;
- Order state;
- price snapshots; or
- idempotency ownership.

A Redis key expiring does not release Inventory. A controlled database
transaction performs release and then updates or invalidates cache state.

Distributed locks are not introduced while the shared MySQL rows provide the
authoritative locking boundary.

## 31. Future AI and Agent Integration

AI is not implemented by V1.

Future AI or agents may:

- rank already-active Offers for a Customer;
- explain Reservation or Order status;
- recommend notification timing;
- summarize Restaurant fulfillment workload; or
- invoke allow-listed Customer Ordering tools for an authenticated actor.

AI and agents must never:

- create eligibility;
- override `NOT_ELIGIBLE` or `REQUIRES_REVIEW`;
- reserve an expired or inactive Offer;
- bypass Customer, Restaurant, or ownership checks;
- bypass idempotency;
- alter quantities without the locked transactional service;
- extend a Reservation or Offer beyond authoritative limits;
- calculate an unvalidated price;
- directly write to MySQL or Redis;
- treat RAG text as executable permission; or
- declare food safe or unsafe.

Agent tools such as `reserveFood()` or `createOrder()` must call the same
application services used by REST controllers. The service revalidates current
state inside the transaction regardless of an earlier AI recommendation.

## 32. Security and Privacy Boundary

Authentication and authorization are not implemented or redesigned here.

Future security must determine:

- which principal owns a Customer;
- which Restaurant users may view or complete Orders;
- whether support operators may access Customer PII;
- rate limits for Reservation and Order creation;
- audit requirements; and
- retention/deletion obligations for Customer data.

Until then:

- ownership-scoped repository queries remain mandatory;
- public UUIDs are used at API boundaries;
- cross-owner resources return `404`;
- responses expose the minimum Customer data required;
- logs, events, AI prompts, and metrics must not contain unnecessary PII; and
- deleting a Customer must not destroy financial or allocation history.

`CLOSED` status is preferred over hard deletion while historical Reservations
and Orders exist.

Customer Ordering mutation and PII read endpoints must not be exposed to an
untrusted production network before authentication and authorization are
implemented. This is a deployment boundary, not a claim that UUID ownership
scoping authenticates a caller.

## 33. Alternatives Considered

### 33.1 One combined Reservation/Order entity

Rejected because temporary expiry/release and durable Order fulfillment have
different lifecycle and audit requirements.

### 33.2 Direct Order without Reservation

Rejected for V1 because the required flow explicitly includes Reservation and
because a short-lived hold separates allocation from Order confirmation.

### 33.3 Offer quantity counters

Deferred. Inventory is already the quantity authority, while active/converted
Reservation sums provide Offer allocation. Duplicate counters would add
reconciliation requirements.

### 33.4 Redis-only Reservations

Rejected because cache loss, eviction, restart, or delayed expiry could break
durable Inventory accounting.

### 33.5 Global unique constraint on Offer

Rejected. One Offer legitimately supports many Customer Reservations and
OrderItems.

### 33.6 Latest Food Eligibility lookup during ordering

Rejected. The Offer retains the exact immutable evaluation that authorized
creation. Ordering validates the selected Offer and current operational state;
it does not silently substitute another evaluation.

### 33.7 One Order spanning multiple Restaurants or currencies

Rejected for V1 because fulfillment ownership, totals, cancellation, future
payment, and event handling become ambiguous.

## 34. Non-Goals

Customer Ordering V1 design does not:

- certify food safety;
- create or modify Food Eligibility Evaluations;
- mutate Surplus Detection;
- modify Offer pricing;
- accept client-supplied prices;
- provide customer authentication;
- process payment;
- calculate tax;
- arrange delivery;
- define refunds;
- define post-completion returns;
- introduce a quantity unit;
- implement a scheduler;
- implement Kafka, Redis, AI, RAG, or agents; or
- modify existing Offer creation behavior.

## 35. Proposed Implementation Sequence

Implementation must remain incremental and requires explicit approval.

Incremental steps:

1. CustomerStatus and Customer persistence design implementation. (Completed)
2. Customer request/response DTO and validation contract. (Completed)
3. Customer transactional creation service and duplicate handling. (Completed)
4. Customer controller, HTTP exception mapping, and OpenAPI. (Completed)
5. ReservationStatus, Reservation entity, constraints, and repositories.
   (Completed)
6. Reservation DTOs, validation, service contract, pricing/TTL snapshots, and
   sequential idempotency foundation. (Completed)
7. Reservation creation controller, HTTP exception mapping, and OpenAPI.
   (Completed)
8. Reservation concurrency-safe allocation transaction. (Completed)
9. Reservation cancellation and expiry service.
10. OrderStatus, Order, and OrderItem persistence.
11. Order creation from Reservations.
12. Order completion. (Completed)
13. Remaining global exception mappings and OpenAPI.
14. Unit, controller, and real-MySQL integration tests.
15. Continue Customer Ordering interview material for each increment.

No implementation step should combine all layers without review.

## 36. Required Future Testing

### 36.1 Entity and persistence tests

- Public UUID generation and uniqueness.
- Enum string persistence.
- Precision and scale.
- Required foreign keys.
- Public/business unique constraints.
- Audit timestamps.
- Optimistic versions.
- Immutable OrderItem snapshots.

Implemented Reservation persistence coverage verifies:

- generated and database-unique public UUIDs;
- string status persistence and required upstream references;
- quantity and monetary precision;
- currency, idempotency key, and request hash persistence;
- optimistic version and audit timestamp initialization;
- customer-scoped idempotency uniqueness; and
- database rejection of non-positive quantity, unit price, or total amount
  and invalid expiry ordering.

### 36.2 Customer tests

Implemented persistence coverage verifies:

- canonical lowercase email persistence and lookup;
- generated public UUID, default `ACTIVE` status, optimistic version, and
  audit timestamps;
- nullable contact phone; and
- database rejection of email values that collide after canonicalization.

Implemented DTO-validation coverage verifies:

- valid request acceptance without DTO-side email lowercasing;
- missing, blank, malformed, and overlength email rejection;
- missing, blank, and overlength display-name rejection;
- optional contact phone and its maximum length; and
- response exclusion of internal ID and optimistic-lock version.

Implemented service coverage verifies:

- transactional Customer creation and approved response mapping;
- `Locale.ROOT` lowercase email canonicalization;
- default `ACTIVE` status, generated public UUID, and audit timestamps;
- optional contact phone;
- exact and mixed-case duplicate feedback through the application pre-check;
- translation of only the named email unique constraint; and
- propagation of unrelated database integrity failures.

Implemented controller coverage verifies:

- `POST /api/v1/customers` returns `201` and delegates to `CustomerService`;
- all Customer request validation constraints return the centralized `400`
  validation response;
- malformed JSON retains the centralized `Malformed request body` response;
- duplicate canonical email maps to the centralized `409` response;
- optional contact phone;
- client-supplied server-controlled properties cannot influence the response;
- internal ID and version are not exposed; and
- OpenAPI response schemas for `201`, `400`, and `409`.

Future service and API coverage must verify:

- suspended and closed Customer behavior; and
- validation errors use the centralized HTTP `400` `ErrorResponse` contract.

### 36.3 Reservation service and API tests

Implemented allocation coverage verifies structural validation, canonical
hashing, the disabled-by-default feature gate, Customer-scoped idempotency,
canonical lock targets, lifecycle/relationship/currency revalidation,
Inventory and Reservation-ledger fail-closed behavior, Offer and Inventory
capacity, server pricing and expiry, available-to-reserved movement, expired
hold release, rollback boundaries, and narrow named-constraint recovery.
Real-MySQL coverage uses independent connections and latch/barrier coordination
to verify actual Inventory lock waiting, runtime `READ-COMMITTED`, post-wait
visibility of a committed ledger change, last-unit competition, concurrent
logical-expiry replay, the real
`uk_reservations_customer_idempotency` loser/recovery path, and rollback after
expiry release or new-Reservation persistence. These tests do not use
`Thread.sleep` for synchronization.

Implemented controller coverage verifies `201` creation and exact service
delegation, required idempotency-header validation, malformed UUID/JSON and DTO
validation `400` responses, Customer/Offer `404` responses, Reservation
conflict `409` responses, approved response isolation, and OpenAPI schemas plus
the required header contract.

Future lifecycle coverage must verify cancellation and the standalone expiry
worker release exactly once, Customer ownership isolation for read/cancel
operations, and concurrency with future Order conversion and cancellation.

### 36.4 Order service and API tests

- Successful same-Restaurant/same-currency Order.
- Multiple OrderItems.
- Empty or duplicate Reservation list.
- Cross-Customer, cross-Restaurant, and cross-currency rejection.
- Expired, cancelled, or converted Reservation rejection.
- One OrderItem per Reservation constraint.
- Server-calculated line totals and Order total.
- Inventory unchanged during Order creation.
- Completion moves `reserved -> sold`.
- Cancellation moves `reserved -> available`.
- Completed and cancelled terminal behavior.
- No partial commit when one item fails.
- Idempotent Order creation.

### 36.5 Real MySQL concurrency tests

- Two Customers compete for the last Inventory quantity.
- Two Customers compete for the last Offer allocation.
- Concurrent cancellation and Order creation for one Reservation.
- Concurrent Order creation using the same Reservation.
- Concurrent completion and cancellation for one Order.
- Same idempotency key submitted concurrently.
- Pessimistic lock waits use independent transactions/connections.
- Lock order is consistent for multi-item Orders.
- Exactly one winning mutation and correct `409` losing behavior.
- Inventory invariant holds after every race.
- No overselling and no negative quantity.

### 36.6 Rollback tests

- Reservation insert failure rolls back Inventory mutation.
- OrderItem failure rolls back Order and all Reservation transitions.
- Completion failure rolls back all Inventory rows.
- Cancellation failure rolls back all releases.
- Named constraint translation does not hide unrelated database failures.

## 37. Open Product and Existing-Code Constraints

The following require confirmation before implementation:

1. Whether a Customer may hold multiple active Reservations for the same
   Offer.
2. Values for configurable limits:
   `foodsaver.ordering.max-reservation-quantity` and
   `foodsaver.ordering.max-reservations-per-order`. No arbitrary hard-coded
   limits are approved.
3. Whether a future security/account phase changes the V1 Customer email
   contract.
4. Retention and anonymization requirements for Customer PII.
5. The future unit-of-measure model.

Existing-code constraints:

- Offer creation does not reserve Inventory.
- Offer has no remaining/reserved/sold counters.
- Inventory already contains available/reserved/sold quantities and
  `@Version`.
- Inventory and Offer creation use an Inventory-first pessimistic lock order.
- Offer expiry is enforced logically even without an expiry scheduler.
- Product and Restaurant currently permit operations that may need historical
  deletion restrictions once Order foreign keys exist.
- The project currently uses Hibernate `ddl-auto=update`; migrations are not
  part of this design step.
- Authentication and authorization are not implemented.

Customer Ordering implementation does not depend on Offer marketplace
read/list APIs. Reservation creation may accept an existing Offer public UUID
and resolve it through an ownership-aware repository query. Offer discovery,
listing, filtering, and search APIs may be implemented independently later.

No open decision may be filled by an invented food-safety rule or delegated to
AI.

## 38. Final Design Decisions

Customer Ordering V1 adopts these decisions:

1. Customer is a profile aggregate, not an authentication credential store.
2. One Reservation holds quantity from exactly one Offer.
3. Inventory remains the quantity-accounting source of truth.
4. Offer capacity is derived from `ACTIVE` and `CONVERTED` Reservations rather
   than duplicated Offer counters.
5. Reservation expiry never exceeds Offer expiry.
6. Order creation converts Reservations but does not move Inventory again.
7. Order completion moves reserved quantity to sold.
8. Order cancellation releases reserved quantity.
9. One Order may contain multiple Reservations only for one Customer,
   Restaurant, and currency.
10. Every OrderItem is an immutable quantity and price snapshot.
11. Inventory is locked before Offer to remain compatible with implemented
    Offer creation.
12. Multi-row locks use ascending internal IDs.
13. Reservation and Order creation require durable idempotency keys and request
    hashes.
14. The database, not Redis, owns Reservation and Order state.
15. Kafka events occur only after commit through a future reliable publication
    mechanism.
16. AI remains advisory and cannot authorize eligibility or allocation.
17. A database-backed expiry processor is required before production so
    abandoned holds are eventually released.
18. Customer Ordering does not automatically transition Inventory status.
19. Order completion does not change Offer or Inventory status.
20. Cancellation is not implemented; a future contract must coordinate it
    with the locked completion transition.
21. Production endpoint exposure waits for the future authentication and
    authorization contract.
22. V1 uses one globally configured Reservation TTL.
23. Maximum Reservation quantity and Reservations per Order are configurable;
    values are not hard-coded in business logic.
24. Reservation creation may use an existing Offer public UUID without waiting
    for Offer discovery/read APIs.
