# Order Management V1

## Scope

Order Management V1 converts exactly one valid `ACTIVE` Reservation into one
`CONFIRMED` Order containing exactly one OrderItem. It does not implement
payment, refund, delivery, cancellation, completion, fulfillment, Kafka,
Redis, AI/RAG, multi-item carts, or partial fulfillment.

An Order is the durable confirmation of a purchase intent. A Reservation is
the quantity and price hold from which that Order is created. Conversion
changes the Reservation from `ACTIVE` to `CONVERTED`; it does not create a new
allocation.

## Persistence model

`customer_orders` stores the public UUID, Customer, Restaurant, `CONFIRMED`
status, total and currency snapshots, customer-scoped idempotency key, request
hash, microsecond timestamps, and optimistic-lock version. The database
uniquely protects the public UUID and `(customer_id, idempotency_key)`.

`order_items` stores the public UUID, parent Order, source Reservation, Offer,
Product, Inventory, product-name snapshot, quantity, unit-price, line-total,
currency, and creation timestamp. Unique constraints on `reservation_id` and
`order_id` enforce at most one OrderItem per Reservation and at most one item
per Order. The conversion transaction creates the Order and its required item
atomically, which supplies the V1 existence guarantee.

Upstream business history is referenced without cascade deletion. APIs expose
public UUIDs and never internal database IDs.

## Conversion transaction

`POST /api/v1/customers/{customerPublicId}/orders` requires an
`Idempotency-Key` and a `reservationPublicId`. The coordinator validates this
input, requires the existing Reservation Allocation activation gate, hashes
the Customer and Reservation public UUIDs, and delegates to a separately
proxied `READ_COMMITTED` transaction.

The command uses the established canonical lock order:

1. Customer — `PESSIMISTIC_WRITE`
2. resolve immutable Reservation lock-target IDs without a lock
3. Restaurant — `PESSIMISTIC_READ`
4. Product — `PESSIMISTIC_READ`
5. Inventory — `PESSIMISTIC_WRITE`
6. Offers — `PESSIMISTIC_WRITE`, ascending database ID
7. Reservations — `PESSIMISTIC_WRITE`, ascending database ID

After all locks are held, one microsecond-normalized transaction timestamp is
captured. The command revalidates ownership, relationships, active/unexpired
Reservation state, currencies, snapshot values, and both ledger invariants.
It then persists the Order and OrderItem, changes the Reservation to
`CONVERTED`, sets `convertedAt`, rechecks the invariants, flushes, and verifies
the persistence result. These operations commit or roll back together.

`GET /api/v1/customers/{customerPublicId}/orders/{orderPublicId}` returns the
customer-owned Order and its single item.

## Inventory and ledger invariants

Order creation deliberately does not change Inventory quantities. Before and
after conversion:

```text
prepared = available + reserved + sold
reserved = SUM(ACTIVE + CONVERTED Reservation.quantity)
```

Because `CONVERTED` remains in the Reservation ledger, decrementing reserved
quantity during Order creation would corrupt the invariant. A future Order
Completion/Fulfillment module owns the eventual `reserved -> sold` movement.
V1 performs no automatic quantity repair; any mismatch fails closed with a
controlled conflict and no business mutation.

## Snapshot pricing

Order and OrderItem monetary values come from the persisted Reservation:

- `Reservation.quantity`
- `Reservation.unitPrice`
- `Reservation.totalAmount`
- `Reservation.currencyCode`

Current Offer or Product prices never reprice the Order. The current locked
Product name is copied into `product_name_snapshot`, after which later Product
name changes cannot alter the OrderItem.

## Idempotency and concurrency

Idempotency is scoped by `(customer_id, idempotency_key)`. The request hash is
derived from the Customer and Reservation public UUIDs:

- same key and same hash replays the original Order;
- same key and different hash returns `409 Conflict`;
- different Customers have independent key scopes.

Named database constraints remain authoritative. A race on the named
customer/idempotency constraint rolls back the failed conversion before a
read-only `REQUIRES_NEW` service verifies the winner. The original command is
then retried for replay. Unrelated integrity failures are not translated as
idempotency races.

Pessimistic lock failures are translated to a controlled retryable conversion
conflict. Concurrent conversion attempts for one Reservation serialize on the
canonical locks; only one OrderItem can survive because `reservation_id` is
also unique.

## Errors and operational boundary

Malformed input is `400`, missing customer-owned resources are `404`, and
expired, non-active, inconsistent, idempotency, or concurrency conditions are
`409`. Responses do not expose SQL errors, stack traces, internal IDs, or
ledger quantities.

Order conversion uses the existing fail-closed allocation activation gate.
Production reconciliation remains deployment/operations work; this document
does not claim that a production baseline has been reconciled or activated.
