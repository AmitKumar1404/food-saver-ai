# Order Completion / Fulfillment V1

## Scope

Fulfillment V1 is one accounting transition:

```text
Order:       CONFIRMED -> COMPLETED
Reservation: CONVERTED -> CONVERTED
Inventory:   reserved -> sold
```

It does not represent payment, delivery, pickup verification, refund, food
safety certification, cancellation, partial fulfillment, or multi-item
fulfillment. The current Order has exactly one immutable OrderItem.

## API

```http
POST /api/v1/customers/{customerPublicId}/orders/{orderPublicId}/complete
```

The request has no body, `Idempotency-Key`, or client timestamp. A first
completion and a completed replay return `200 OK` with `OrderResponse`.
`completedAt` is null for confirmed Orders and present for completed Orders.
Requests and responses expose public UUIDs only.

Completion is Order-scoped within the Customer-owned API above. V1 does not
implement a Restaurant-scoped fulfillment endpoint.

Malformed UUIDs return `400`; a missing Customer or Order outside that
Customer's scope returns `404`; lifecycle, relationship, ledger, Inventory,
and supported concurrency conflicts return sanitized `409` responses.
Unexpected infrastructure failures use the existing sanitized `500` boundary.
No response exposes internal IDs, quantities used for diagnostics, SQL,
constraint names, lock details, or stack traces.

## Transaction and lock order

A separately proxied command runs at `READ_COMMITTED` and uses exactly:

```text
Customer PESSIMISTIC_WRITE
    -> Order PESSIMISTIC_WRITE
        -> Restaurant PESSIMISTIC_READ
            -> Product PESSIMISTIC_READ
                -> Inventory PESSIMISTIC_WRITE
                    -> Offers PESSIMISTIC_WRITE by ascending internal ID
                        -> Reservations PESSIMISTIC_WRITE by ascending internal ID
```

After Customer is locked, immutable target IDs are resolved without locking.
After Order is locked, an already completed Order is validated and returned
without locking or mutating Inventory. OrderItem is immutable and does not
need a separate pessimistic lock, but it is reloaded and validated in the
transaction.

For a confirmed Order, all locked relationships are checked from persisted
state: Order ownership and Restaurant; OrderItem parent, Reservation, Offer,
Product, and Inventory; converted Reservation lifecycle; equal item and
Reservation quantities; and equal unit-price, line-total, and currency
snapshots. Current Offer or Product prices never reprice fulfillment.

One server timestamp is captured after all locks and normalized to MySQL
microsecond precision. Completion applies:

```text
availableAfter = availableBefore
preparedAfter  = preparedBefore
reservedAfter  = reservedBefore - quantity
soldAfter      = soldBefore + quantity
statusAfter    = statusBefore

order.status      = COMPLETED
order.completedAt = transactionTime
order.updatedAt   = transactionTime
```

Order and Inventory are flushed, then expected deltas, persistence, lifecycle,
and both invariants are revalidated before commit. Any failure rolls back the
Order transition and Inventory movement together. Reservation remains
`CONVERTED`; Inventory and Offer status are unchanged.

## Ledger invariants

The Inventory equation is always:

```text
prepared = available + reserved + sold
```

Outstanding reserved Inventory is:

```text
reserved =
    SUM(ACTIVE Reservation.quantity)
    + SUM(CONVERTED Reservation.quantity linked to CONFIRMED Orders)
```

A converted Reservation linked to a completed Order no longer contributes.
Every converted Reservation must link through exactly one OrderItem to an
Order; orphan converted rows fail closed. Allocation, conversion, completion,
startup preflight, and runtime checks share this completion-aware definition.
No path silently repairs production data.

## Retry and concurrency

No second idempotency mechanism exists. The locked Order lifecycle is the
idempotency boundary:

- `CONFIRMED`: perform one Inventory movement and complete.
- `COMPLETED`: return the persisted result unchanged.
- failed transaction: Order remains `CONFIRMED` and can be retried.

The Order lock serializes two attempts for the same Order. The Inventory lock
serializes different Orders sharing Inventory and prevents lost updates,
negative reserved quantity, and double increments. Ordered Offer and
Reservation locks retain compatibility with allocation and conversion.
Narrow pessimistic-lock failures map to a completion conflict; unrelated data
access or integrity failures are not broadly translated.

## Persistence and production rollout

`OrderStatus` contains `CONFIRMED` and `COMPLETED`. `completed_at` is
`TIMESTAMP(6)`. The database lifecycle check requires:

```text
CONFIRMED -> completed_at IS NULL
COMPLETED -> completed_at IS NOT NULL
```

Production must use an explicit migration for the enum-compatible status
value, column, and check constraint. Hibernate `ddl-auto=update` is not a
production migration strategy.

Before activating completion, an operational reconciliation must establish:

- every `CONVERTED` Reservation has exactly one OrderItem and Order;
- confirmed converted quantities reconcile to Inventory reserved quantity;
- every Inventory equation is valid;
- no orphan converted Reservation exists; and
- no ledger mismatch remains.

Historical sold quantities must not be invented from existing Orders, and
inconsistent production data must not be silently corrected.

## Testing boundary

Real-MySQL coverage verifies successful completion and replay, retry after
rollback, exact quantity movement, Reservation and Inventory status
preservation, pricing mismatch rejection, invalid Inventory equation and
orphan converted Reservation rejection, missing OrderItem rejection,
microsecond timestamp reload equality and column precision, database lifecycle
checks, generated Order/Offer/Reservation locking SQL, same-Order
serialization, shared-Inventory lock waits, `READ_COMMITTED` visibility, and
rollback after mutation and flush. Preflight tests verify that orphan converted
Reservations prevent activation. Controller and unit tests cover completion
delegation, sanitized `400`, `404`, and `409` contracts, lock translation,
server-owned completion timestamps, response mapping, and public UUID-only
output.

Future multi-item fulfillment would keep one atomic transaction and sort all
Restaurant, Product, Inventory, Offer, and Reservation lock targets by
internal ID before mutation. It is not implemented in V1.
