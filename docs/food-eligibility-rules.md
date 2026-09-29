# Food Eligibility Rules

## 1. Module Overview

The Food Eligibility Rules module is the controlled decision layer between
Surplus Food Detection and any future Offer workflow. It evaluates a specific
surplus detection snapshot against deterministic, versioned, authoritative
platform policies and records an auditable eligibility result.

This document defines the design only. No Java classes, database tables,
configuration, API endpoints, events, caches, AI integrations, or tests are
implemented in this phase.

The central boundary is:

> `POTENTIAL_SURPLUS` does not mean `SAFE_FOR_SALE`.

The module produces a platform workflow decision. It does not issue a
food-safety certification, replace applicable law, or authorize an Offer by
itself.

## 2. Business Problem

Surplus Detection identifies Inventory whose available quantity meets a
configured surplus threshold. Quantity alone cannot establish whether that
Inventory is eligible to continue toward an Offer.

Before the platform allows an Offer workflow to begin, it needs a separate
decision point that can:

- Apply approved policies consistently.
- Use the policy version applicable at the time of evaluation.
- Respect Restaurant ownership and jurisdiction.
- Reject automatic progression when required facts are absent or conflicting.
- Preserve the facts, rules, and outcomes used for audit.
- Route uncertain cases to qualified human review.

Without this layer, a `POTENTIAL_SURPLUS` signal could be incorrectly treated
as approval. That would mix demand analysis, platform eligibility, food
safety, and Offer creation into one unsafe responsibility.

## 3. Purpose of Food Eligibility Rules

The module answers this limited question:

> May this specific surplus detection proceed to the next controlled platform
> workflow under the applicable, authoritative policy version?

It does not answer:

- Whether the food is medically or scientifically safe.
- Whether every legal obligation has been satisfied outside the facts known to
  the platform.
- Whether an Offer should be created, priced, published, or purchased.
- Whether Inventory should be reserved, sold, reduced, or otherwise changed.

The purpose is to provide an explainable gate with deterministic outcomes,
clear evidence requirements, and an audit trail.

## 4. Domain Relationships and Responsibilities

The modules remain separate:

- **Restaurant** identifies the owning outlet, jurisdictional context,
  business type, timezone, and platform lifecycle.
- **Product** defines the Restaurant-owned catalog item and its category,
  status, and descriptive information.
- **Inventory** is the authoritative dated quantity state for a Product. Its
  quantity invariant remains under Inventory Management.
- **Surplus Detection** records whether available Inventory met the configured
  surplus threshold at a point in time.
- **Food Eligibility** evaluates that specific detection and its authoritative
  facts against a specific policy version.
- **Future Offer** may consume an eligible evaluation but must independently
  enforce its own authorization, pricing, quantity, and lifecycle rules.

The conceptual relationship is:

```text
Restaurant
    └── Product
            └── Inventory
                    └── SurplusDetection
                            └── FoodEligibilityEvaluation
                                    └── Future Offer
```

An Offer must never be created directly from `POTENTIAL_SURPLUS`. It may only
be considered after a successful eligibility decision and any other required
workflow gates.

## 5. Critical Safety Boundary

The following statements are architectural requirements:

- `POTENTIAL_SURPLUS` means only that available quantity met the Surplus
  Detection threshold.
- `POTENTIAL_SURPLUS` does not mean safe, unsafe, compliant, approved, or
  eligible for sale.
- `ELIGIBLE_FOR_OFFER` is a platform workflow result, not a food-safety
  certification.
- `NOT_ELIGIBLE` does not necessarily mean unsafe; it means the applicable
  platform policy blocked progression.
- `REQUIRES_REVIEW` means automatic progression is prohibited until the
  defined review process is completed.
- Surplus food is not automatically unsafe.
- Unsafe or disallowed food must never become eligible merely because it is
  surplus.
- AI, RAG, operators, and downstream modules must not bypass hard,
  authoritative controls.

The module must not invent regulatory or scientific rules. Actual policy
content must come from approved authoritative sources and qualified owners.

## 6. V1 Eligibility States

V1 uses exactly these evaluation states:

### `ELIGIBLE_FOR_OFFER`

All required deterministic rules for the applicable policy version passed
using complete, current, and non-conflicting facts.

This state permits consideration by a future Offer module. It does not create
an Offer and does not certify food safety.

### `NOT_ELIGIBLE`

At least one authoritative blocking rule failed, or policy explicitly requires
the workflow to stop for the evaluated facts.

The reason must be represented by stable reason codes and rule results. This
state must not be presented as a general statement that the food is unsafe.

### `REQUIRES_REVIEW`

The system cannot automatically determine eligibility because required facts
are missing, conflicting, stale, outside automated policy coverage, or
explicitly require qualified review.

This state must block automatic Offer progression. Review must use a separate,
authorized, auditable process.

No `SAFE`, `UNSAFE`, `CERTIFIED`, or `APPROVED_FOR_SALE` status is introduced
by this module.

## V1 Implementation Prerequisites

Implementation must not begin until all of the following prerequisites are
satisfied:

- An approved, immutable V1 policy bundle exists.
- The applicable policy owner and governance contract are defined.
- Policy-effective timestamp semantics are explicitly approved.
- A typed fact contract defines every fact required by V1, including its
  source and meaning.
- The initial deterministic rule set and stable reason codes are approved.

These prerequisites must be supplied by accountable policy owners. Developers,
AI, and RAG must not invent missing food-safety rules, limits, thresholds, or
policy meaning.

### V1 implementation scope

V1 implementation is limited to:

- One approved, immutable policy bundle.
- Small, explicit deterministic rule components.
- Existing FoodSaver data as the initial fact source where that data is
  sufficient under the approved fact contract.
- A Food Eligibility evaluation entity.
- Rule-result persistence only to the extent required for audit.
- An ownership-aware API.
- The three V1 eligibility states.
- Immutable evaluation history.
- Integration with the existing centralized exception-handling approach.
- Focused unit and integration tests.
- No AI decision-making or integration.

### Future architecture

The following remain future architecture and must not be included in the
initial V1 implementation:

- A dynamic policy registry.
- Runtime policy authoring.
- External fact providers.
- Complex idempotency infrastructure.
- Advanced concurrency mechanisms.
- Kafka.
- Redis.
- RAG.
- Agentic AI.
- A complete human-review workflow.

## 7. Rule Evaluation Architecture

The V1 architecture keeps HTTP handling, orchestration, explicit rule
evaluation, and persistence separate:

```text
FoodEligibilityController
        ↓
FoodEligibilityService
        ├── Ownership-aware data lookup
        ├── Approved immutable policy bundle
        ├── Explicit deterministic rule components
        └── Evaluation persistence
```

Responsibilities:

- The controller handles HTTP binding, validation, and response status only.
- The service owns the transaction, ownership checks, policy selection,
  evaluation orchestration, and response mapping.
- V1 reads existing FoodSaver records directly through ownership-aware
  repositories where the approved fact contract permits those records as
  authoritative inputs.
- The immutable policy bundle supplies the applicable approved V1 policy.
- Deterministic rule evaluators produce explicit outcomes and reason codes.
- Repositories persist evaluations and rule-result history.

The implementation should start with small explicit rule components rather
than a generic scripting engine. A rules engine should be introduced only if
measured policy complexity justifies the operational and security cost.
Dynamic policy resolution and external fact-provider abstractions belong to
future architecture.

## 8. Required Input Facts

An evaluation must use facts with known provenance. V1 should distinguish
facts that already exist from facts that require future authoritative sources.

Existing platform facts include:

- Restaurant public identity and internal ownership relationship.
- Restaurant business type, country, location context, timezone, and platform
  status.
- Product public identity, owning Restaurant, category, and lifecycle status.
- Inventory public identity, owning Restaurant and Product, inventory date,
  lifecycle status, available quantity, and optimistic-lock version.
- Surplus Detection public identity, owning Inventory, detected quantity,
  threshold quantity, result status, and detection timestamp.
- Evaluation time.

Policy-dependent facts may later include approved records or attestations
about handling, packaging, storage, labeling, traceability, or other
jurisdiction-specific requirements. Their exact fields and acceptable values
must be defined by qualified policy owners before implementation.

The module must not infer missing policy facts from Product names,
descriptions, AI output, or unrelated metadata.

Each fact should have:

- A stable fact name.
- A typed value.
- A source reference.
- A captured or effective timestamp where relevant.
- A trust classification or authority level.
- The evaluated record version where concurrency matters.

## 9. Rule Categories

The design supports rule categories without defining unapproved limits:

1. **Identity and ownership rules**  
   Verify that Restaurant, Product, Inventory, and Surplus Detection belong to
   the same ownership chain.

2. **Resource lifecycle rules**  
   Apply authoritative policy to relevant Restaurant, Product, Inventory, and
   detection lifecycle states.

3. **Surplus precondition rules**  
   Confirm that the selected detection is the intended input and that policy
   permits evaluation of its status.

4. **Quantity consistency rules**  
   Verify policy-required consistency without modifying Inventory or weakening
   its quantity invariant.

5. **Evidence completeness rules**  
   Determine whether every mandatory authoritative fact is present and usable.

6. **Jurisdiction applicability rules**  
   Select and apply only policy approved for the Restaurant's jurisdiction and
   effective date.

7. **Business and Product applicability rules**  
   Select rules by supported business type, Product category, or other
   explicitly governed classification.

8. **Authoritative restriction rules**  
   Apply hard prohibitions or review requirements supplied by approved policy
   owners.

No temperature, elapsed-time, shelf-life, medical, or handling limit is
defined in this design.

## 10. Configurable Policy and Rule Design

Rules must not be scattered as magic values across services. V1 should expose
a typed policy boundary with:

- Stable policy key.
- Immutable policy version.
- Effective start and optional end.
- Jurisdiction and applicability selectors.
- Ordered deterministic rules.
- Required fact definitions.
- Stable result and reason codes.
- Authoritative source reference.
- Policy integrity metadata.

Configuration must be validated at application startup or policy publication
time. Invalid, ambiguous, overlapping, or incomplete policy configuration must
fail closed and must never default to `ELIGIBLE_FOR_OFFER`.

Policy values must retain their declared types and precision. The evaluator
must not silently coerce, round, or substitute missing values.

V1 should prefer a reviewed, version-controlled policy bundle deployed through
a controlled release process. Runtime policy editing is out of scope until
authorization, approval workflow, validation, signing, and rollback are
designed.

## 11. Authoritative Rule and Policy Source

Every production rule must identify an approved authority, such as:

- A versioned internal compliance policy approved by accountable owners.
- Applicable legal or regulatory material reviewed by qualified specialists.
- A controlled operational policy whose owner, approval, and scope are known.
- A verified external system with an explicit trust contract.

A URL or retrieved document alone is not sufficient authority. Policy records
should capture:

- Policy owner.
- Approval status and approver identity.
- Source reference and source version.
- Jurisdiction and scope.
- Effective dates.
- Publication timestamp.
- Integrity hash or equivalent tamper-evidence where appropriate.

Application developers and AI systems must not invent rule content to fill a
policy gap.

## 12. Rule Versioning and Effective Dates

Published policy versions must be immutable. A correction creates a new
version rather than rewriting the version used by historical evaluations.

Policy resolution should use:

- The approved policy-effective timestamp defined by the governance contract.
- Restaurant jurisdiction and applicable business context.
- Product or Inventory classifications explicitly supported by policy.
- `effectiveFrom` inclusive.
- `effectiveTo` exclusive when present.

Exactly one applicable policy version should be selected during a normal
evaluation. If no approved policy applies, the service creates an evaluation
with `REQUIRES_REVIEW`. If multiple equally applicable published policies are
found, the service must not choose one automatically and creates an evaluation
with `REQUIRES_REVIEW`. Both outcomes block automatic Offer progression.

When one policy applies, the evaluation stores its policy key and immutable
version so the decision remains reproducible after later policy changes. A
no-match or ambiguous-match evaluation instead stores the policy-resolution
reason and any safe candidate references needed for audit without claiming
that one policy was applied.

Policy-effective timestamp semantics are an authoritative governance decision.
V1 must not assume detection time, evaluation time, or Offer time without an
approved policy contract. Before implementation, policy owners must explicitly
define which timestamp determines applicability. Once approved, the selected
timestamp, its semantic role, and the applied policy version must be persisted
with the evaluation.

## 13. Jurisdiction and Business Applicability

Policy applicability may depend on:

- Restaurant country and supported sub-jurisdiction.
- Restaurant business type.
- Product category.
- Operational channel or workflow type.
- Policy effective date.

Applicability must be explicit and deterministic. Free-text addresses,
Product names, or AI classification must not silently determine jurisdiction
or policy.

The initial jurisdiction model should use validated codes from authoritative
Restaurant data. More detailed jurisdiction support should be added only after
its source, precedence, and boundary rules are defined.

If no approved policy covers the context, the result cannot be
`ELIGIBLE_FOR_OFFER`. V1 creates a `REQUIRES_REVIEW` evaluation and blocks
automatic Offer progression.

## 14. Missing or Conflicting Information

Missing information must not be treated as a passing fact.

V1 behavior:

- A missing required authoritative fact produces `REQUIRES_REVIEW`, unless the
  applicable policy explicitly defines a deterministic blocking outcome.
- Conflicting authoritative facts produce `REQUIRES_REVIEW`.
- If no approved policy applies to the evaluated context, create an evaluation
  with `REQUIRES_REVIEW`.
- If multiple equally applicable published policies are found, do not select
  one automatically; create an evaluation with `REQUIRES_REVIEW`.
- Every `REQUIRES_REVIEW` outcome blocks automatic Offer progression.
- Invalid ownership produces a not-found response rather than an eligibility
  result.

Invalid policy configuration is different from a valid runtime context that
has no applicable policy. Invalid or overlapping policy configuration must
fail validation before the policy bundle becomes available for evaluation.
An invalid bundle must not be published or activated, and its defects must not
be represented as a normal eligibility result.

The result must record which fact or policy requirement prevented automatic
evaluation using safe, stable reason codes. Sensitive evidence must not be
copied into client-facing messages or logs.

## 15. Human and Qualified Review Boundary

`REQUIRES_REVIEW` is a workflow stop, not an invitation for any user to
override rules.

A future review process must define:

- Which roles are qualified to review each reason.
- Which evidence they may access.
- Whether they may resolve missing facts or only record an external decision.
- Required comments or evidence references.
- Separation of duties where applicable.
- Review expiration and re-evaluation rules.
- Complete actor and timestamp audit.

Human review must not edit the original evaluation snapshot. A review decision
should create a separate immutable review record and, when appropriate, a new
eligibility evaluation or explicit reviewed outcome.

Hard prohibitions cannot be bypassed by ordinary reviewer discretion.

## 16. Deterministic Rules and Future AI Assistance

V1 evaluation is deterministic:

```text
Same facts + same policy version = same rule outcomes
```

This makes decisions testable, reproducible, and explainable.

Future AI may assist with:

- Organizing evidence for a reviewer.
- Identifying missing information.
- Summarizing rule outcomes.
- Suggesting workflow prioritization.
- Detecting possible data inconsistencies for review.

AI output is not an authoritative fact unless an approved policy explicitly
defines a controlled validation process for that source. AI must not write an
eligible result directly, alter policy, or bypass a failed rule.

## 17. Why AI Is Not the Final Food-Safety Authority

AI models can be probabilistic, non-deterministic, stale, incomplete, or
incorrect. Their output can also be affected by ambiguous prompts, missing
context, and model changes.

Therefore:

- AI cannot certify food safety.
- AI cannot override authoritative deterministic restrictions.
- AI cannot convert `REQUIRES_REVIEW` or `NOT_ELIGIBLE` into
  `ELIGIBLE_FOR_OFFER`.
- AI cannot directly change Inventory or create Offers.
- AI-assisted actions require scoped tools, validation, audit, and human
  approval where policy requires it.

## 18. Why RAG Cannot Override Deterministic Controls

RAG may retrieve relevant approved material, but retrieval is not proof that
the material is current, applicable, complete, or authoritative.

Future RAG usage must:

- Search only approved, versioned sources.
- Preserve citations and source versions.
- Filter by jurisdiction and effective date.
- Distinguish retrieved text from executable policy.
- Treat conflicting or missing sources as review conditions.
- Never execute retrieved prose as an unrestricted rule.

Deterministic policy remains the enforcement boundary. RAG can support
explanation and review, not bypass controls.

## 19. Database Design Proposal

V1 should persist immutable evaluation snapshots. Suggested table:

```text
food_eligibility_evaluations
```

Proposed fields:

- `id` (`BIGINT` / `Long`): internal identity primary key.
- `public_id` (`CHAR(36)` / `UUID`): externally safe immutable identifier.
- `surplus_detection_id` (`BIGINT`): required foreign key to the evaluated
  `surplus_detections.id`.
- `policy_key` (`VARCHAR`, nullable for no-match/ambiguous-match outcomes):
  stable policy family identifier when one policy is applied.
- `policy_version` (`VARCHAR`, nullable for no-match/ambiguous-match outcomes):
  immutable version used when one policy is applied.
- `policy_source_reference` (`VARCHAR`, nullable): safe reference to the
  authoritative source or registry record when one policy is applied.
- `policy_resolution_reason` (`VARCHAR`): stable reason when no policy or
  multiple equally applicable policies prevent selection.
- `status` (`VARCHAR(32)`): one of the three V1 eligibility states.
- `evaluated_inventory_version` (`BIGINT`): Inventory optimistic-lock version
  observed during evaluation.
- `evaluated_available_quantity` (`DECIMAL(12,3)`): quantity fact observed
  without changing Inventory.
- `evaluated_at` (`TIMESTAMP` / `Instant`): decision time.
- `created_at` (`TIMESTAMP` / `Instant`): persistence creation time.
- `updated_at` (`TIMESTAMP` / `Instant`): persistence update time; snapshots
  should normally remain immutable.

Suggested constraints and indexes:

- Primary key on `id`.
- Unique constraint on `public_id`.
- Foreign key to `surplus_detections.id`.
- Index on `(surplus_detection_id, evaluated_at)`.
- Index on `(status, evaluated_at)` only for an implemented operational query.
- No unique constraint on `surplus_detection_id`, because legitimate
  re-evaluation under a new policy or changed facts may be required.

Proposed child table:

```text
food_eligibility_rule_results
```

Each row records:

- Parent evaluation ID.
- Stable rule code and rule version.
- Rule outcome such as pass, block, review, or not applicable.
- Stable reason code.
- Authoritative source reference.
- Non-sensitive fact references needed for explanation.
- Evaluation timestamp.

The exact rule-result schema should be finalized with audit and policy-owner
requirements. Arbitrary executable expressions or untrusted scripts must not
be stored and executed from the database.

A future policy registry may use separate immutable policy-version and
applicability records. V1 may instead deploy a validated version-controlled
policy bundle. V1 persists its key, version, and source reference when one
policy applies; otherwise it persists the deterministic policy-resolution
reason without claiming a selected version.

## 20. Entity Relationship Proposal

The proposed persistence relationship is:

```text
SurplusDetection
        1
        |
        N
FoodEligibilityEvaluation
        1
        |
        N
FoodEligibilityRuleResult
```

Restaurant and Product remain reachable through:

```text
FoodEligibilityEvaluation
    → SurplusDetection
        → Inventory
            → Restaurant
            → Product
```

The evaluation should not add independent mutable Restaurant or Product
relationships that could contradict Inventory ownership. It may persist
explicit fact snapshots or source versions required for audit, but those must
be clearly named as evaluated facts rather than live ownership references.

A future Offer should reference the eligibility evaluation that authorized its
workflow, preserving traceability to the policy and rule results.

## 21. API Design Proposal

An ownership-scoped evaluation endpoint could be:

```text
POST /api/v1/restaurants/{restaurantPublicId}/inventory/{inventoryPublicId}/surplus-detections/{surplusDetectionPublicId}/eligibility-evaluations
```

Expected success:

- `201 Created`
- Body: a Food Eligibility response DTO

A history endpoint could be:

```text
GET /api/v1/restaurants/{restaurantPublicId}/inventory/{inventoryPublicId}/surplus-detections/{surplusDetectionPublicId}/eligibility-evaluations
```

It should return snapshots newest first only after validating the complete
Restaurant → Inventory → Surplus Detection ownership chain.

A single evaluation endpoint could later use:

```text
GET /api/v1/restaurants/{restaurantPublicId}/eligibility-evaluations/{evaluationPublicId}
```

The repository lookup must include Restaurant ownership through the entity
relationship. A public evaluation ID alone is not sufficient authorization.

## 22. Request and Response Responsibility

For V1, identifiers should come from the path and policy selection should be
server controlled. A normal client must not provide:

- Eligibility status.
- Rule outcomes.
- Policy version.
- Authoritative facts.
- Inventory quantities.
- Internal IDs.
- Audit timestamps.
- Reviewer identity or approval.

If idempotency is implemented, the client may supply a validated idempotency
key through a defined header.

The response may expose:

- Evaluation public ID.
- Surplus Detection public ID.
- Inventory public ID.
- Eligibility status.
- Policy key and version when one policy was selected.
- Policy-resolution reason for a no-match or ambiguous-match review result.
- Safe rule outcome summaries and reason codes.
- Evaluation timestamp.
- Creation timestamp.

The response must not expose:

- Internal database IDs.
- JPA entities.
- Internal stack traces or policy-engine details.
- Sensitive evidence.
- An assertion that the food is certified safe.

## 23. Error Handling Proposal

Controllers should not catch domain exceptions. Centralized exception handling
should use the existing `ErrorResponse` structure.

Proposed behavior:

- `400 Bad Request`: malformed identifiers or invalid request metadata.
- `404 Not Found`: Restaurant, ownership-scoped Inventory, Surplus Detection,
  or evaluation is unavailable in the requested ownership context.
- `409 Conflict`: a defined idempotency conflict or stale source state prevents
  creating the requested snapshot.

A future architecture that introduces external authoritative dependencies may
define a safe `503 Service Unavailable` contract. V1 has no external fact
provider and does not add this behavior.

`REQUIRES_REVIEW` and `NOT_ELIGIBLE` are successful evaluation outcomes, not
HTTP errors. A completed evaluation should normally return `201 Created`
regardless of which eligibility state was produced.

Unexpected failures must not expose SQL, policy internals, source documents,
credentials, or stack traces.

## 24. Audit and History Requirements

Every evaluation must be explainable later. Audit data should include:

- Evaluation public ID and timestamp.
- Restaurant ownership path.
- Source record public IDs and relevant versions.
- Policy key, version, source reference, and effective period when one policy
  applies.
- Policy-resolution reason and safe candidate references when no policy is
  selected.
- Facts used and their provenance.
- Every evaluated rule and outcome.
- Final state and stable reason codes.
- Idempotency key or request identifier where applicable.
- Actor or service identity that initiated evaluation.
- Review linkage when review occurs later.

Historical evaluation and rule-result records should be append-only. Corrections
should create new records linked to superseded records rather than rewriting
the original decision.

Retention, legal access, privacy, and evidence-redaction requirements must be
defined before production.

## 25. Idempotency Considerations

Multiple evaluations for one Surplus Detection may be legitimate when:

- Inventory facts changed.
- A new policy version became effective.
- Missing facts were supplied.
- A qualified review requested re-evaluation.

An arbitrary unique constraint on `surplus_detection_id` would therefore be
incorrect.

V1 must distinguish an intentional re-evaluation from a retry. Options include:

- A client or gateway idempotency key.
- A unique request identifier.
- A server-computed fingerprint over detection ID, Inventory version, policy
  version, and normalized authoritative facts.

The selected key and replay period must be documented. A repeated idempotent
request should return the original result rather than execute rules again.
Idempotency must not cause an old eligible result to be reused after relevant
facts or policy changed.

## 26. Concurrency and Consistency

Inventory or policy state may change while evaluation is running.

V1 should:

1. Resolve Restaurant ownership.
2. Load the Inventory and Surplus Detection in the same service transaction.
3. Attempt to resolve one immutable policy version and retain a deterministic
   resolution reason when none can be selected.
4. Capture the Inventory version and relevant facts.
5. Evaluate deterministic rules.
6. Verify freshness according to the approved consistency strategy.
7. Persist the evaluation and rule results atomically.

Reading an entity with `@Version` does not by itself detect a concurrent update
when the evaluation does not update Inventory. The implementation must choose
and test an explicit strategy, such as a final version check, optimistic read
lock, or accepted point-in-time snapshot semantics.

The evaluation transaction must never mutate Inventory quantities. If source
state becomes stale, the result should be rejected, retried safely, or marked
for review according to policy; it must not silently claim current
eligibility.

Policy publication must also prevent overlapping or partially visible policy
versions.

## 27. Future Kafka Events

Possible future events include:

- `FoodEligibilityEvaluated`
- `FoodEligibilityReviewRequired`
- `FoodEligibilityChanged`
- `OfferCreated`

Events are not part of V1. If introduced, they should:

- Be published only after the database transaction commits.
- Use a transactional outbox or equivalent reliability pattern.
- Carry public identifiers and policy version, not internal IDs.
- Use versioned schemas.
- Avoid sensitive evidence.
- Never instruct consumers to bypass eligibility or safety controls.

`OfferCreated` belongs to the future Offer module, not this module.

## 28. Future Redis Usage

Possible future uses include:

- Short-lived caching of immutable policy bundles.
- Idempotency records.
- Rate limiting.
- Short-lived workflow coordination.

Redis must not be the source of truth for:

- Inventory quantities.
- Eligibility evaluations.
- Rule-result history.
- Policy approval or policy versions.
- Human review records.

Cache keys must include policy version and ownership context where applicable.
TTL, invalidation, fail-closed behavior, and cache poisoning protections must
be designed before use.

## 29. Future RAG Integration

RAG may later help qualified users locate:

- The authoritative source supporting a rule.
- Policy explanations.
- Review guidance.
- Version history.

The retrieval corpus must contain approved, versioned material with
jurisdiction and effective-date metadata. Responses must preserve citations.

RAG output cannot:

- Become executable policy automatically.
- Supply missing authoritative facts without verification.
- Change eligibility status.
- Override a deterministic block.
- Declare food safe or unsafe.

## 30. Future Agentic AI Integration

Future agents may coordinate approved workflows such as:

- Requesting missing evidence from an authorized system.
- Preparing a review queue.
- Summarizing deterministic outcomes.
- Recommending that an authorized service initiate re-evaluation.

Agent tools must use:

- Least-privilege permissions.
- Typed inputs and outputs.
- Ownership and authorization checks.
- Deterministic validation before execution.
- Idempotency.
- Complete audit logs.
- Human approval for sensitive actions.

Agents must not directly modify Inventory, publish policy, set eligibility,
certify food safety, or create unrestricted Offers.

## 31. Security and Authorization

UUIDs and ownership-aware repository queries are defense in depth, not a
replacement for authentication and authorization.

Future security must define:

- Which Restaurant users may request or view evaluations.
- Which platform roles may publish policies.
- Which qualified roles may perform each review type.
- Tenant isolation for all reads and writes.
- Least-privilege access to evidence.
- Protection against identifier enumeration.
- Rate limits for evaluation requests.
- Audit of policy publication, evaluation, review, and access.

All lookup paths must include Restaurant ownership. A caller must not retrieve
another Restaurant's Inventory, detection, evaluation, rule results, or
evidence through a public UUID.

Policy configuration, source references, and review tools require stronger
administrative authorization than ordinary Restaurant operations.

## 32. Testing Strategy

### Deterministic outcome tests

- Same facts and policy version produce the same result.
- Every passing required rule produces `ELIGIBLE_FOR_OFFER`.
- A blocking rule produces `NOT_ELIGIBLE`.
- A missing required fact produces the policy-defined review/block result.
- Conflicting facts produce `REQUIRES_REVIEW`.
- No result uses a safety-certification status.

### Policy resolution tests

- Correct policy is selected by jurisdiction, applicability, and effective
  date.
- Boundary behavior for effective dates is explicit.
- No applicable policy blocks automatic eligibility.
- Overlapping applicable policies fail closed.
- Invalid policy configuration is rejected before evaluation.
- Historical evaluation retains its original policy version when one was
  selected, or its policy-resolution reason when none was selected.

### Ownership and authorization tests

- Missing Restaurant returns not found.
- Inventory outside the requested Restaurant is inaccessible.
- Surplus Detection outside the requested Inventory is inaccessible.
- Evaluation history is ownership scoped.
- Unauthorized policy or review operations are rejected when security exists.

### Persistence and audit tests

- Evaluation and rule results persist atomically.
- Public IDs and timestamps are generated.
- Internal IDs and entities are not exposed.
- Facts, the selected policy version or policy-resolution reason, outcomes,
  and reason codes are retained.
- Re-evaluation creates history rather than overwriting an earlier snapshot.

### Concurrency and idempotency tests

- Inventory changes during evaluation.
- Stale Inventory version behavior follows the chosen consistency contract.
- Concurrent duplicate requests produce one idempotent result when applicable.
- Legitimate evaluation under a new policy version remains possible.
- Policy publication cannot expose overlapping partial versions.

### Safety-boundary tests

- Evaluation never changes Inventory quantities.
- Evaluation never creates an Offer.
- `POTENTIAL_SURPLUS` alone cannot produce eligibility.
- AI or RAG output cannot bypass a deterministic block.
- `REQUIRES_REVIEW` cannot automatically progress.

### API and exception tests

- Successful evaluation returns `201 Created`.
- Completed non-eligible and review outcomes remain successful evaluations.
- Validation errors use `400`.
- Ownership-aware missing resources use `404`.
- Defined conflicts use `409`.
- Responses use the existing `ErrorResponse` contract where applicable.

## 33. Explicitly Out of Scope for V1

V1 does not include:

- Invented food-safety limits, temperatures, times, or shelf-life values.
- Food-safety certification.
- Medical advice.
- Dynamic regulatory interpretation.
- Automatic Offer creation, pricing, publication, reservation, or ordering.
- Inventory quantity modification.
- Human-review UI or a complete reviewer credentialing system.
- Runtime policy authoring by Restaurant users.
- A dynamic policy registry.
- External fact providers.
- Complex idempotency infrastructure.
- Advanced concurrency mechanisms.
- AI or LLM decision authority.
- RAG-based rule execution.
- Kafka producers or consumers.
- Redis caching or workflow storage.
- Agentic tool execution.
- Demand prediction or dynamic pricing.
- Replacement of qualified legal, regulatory, scientific, or operational
  policy ownership.

## 34. Future Roadmap

### V1: Deterministic eligibility foundation

- Ownership-aware evaluation of a Surplus Detection.
- One approved, immutable policy bundle.
- Explicit deterministic rule components.
- Existing FoodSaver records as facts where the approved fact contract permits.
- A Food Eligibility evaluation entity.
- Rule-result persistence only where required for audit.
- Three eligibility states.
- Immutable evaluation history.
- Existing centralized exception handling.
- Focused unit and integration tests.
- No Inventory mutation or Offer creation.
- No AI.

### V2: Controlled policy registry

- Authorized policy publication workflow.
- Immutable versions and effective dates.
- Jurisdiction and business applicability management.
- Validation, approval, rollback, and integrity controls.

### V3: Qualified review workflow

- Role-based review queues.
- Evidence references and reviewer decisions.
- Expiration and re-evaluation behavior.
- Complete actor audit and separation of duties.

### V4: Reliable integration

- Versioned Kafka events through an outbox.
- Carefully scoped Redis caching and idempotency.
- Downstream Offer integration that requires a valid eligible evaluation.

### V5: Guarded RAG and Agentic AI assistance

- Retrieval from approved, versioned policy sources.
- Citation-backed reviewer assistance.
- Controlled tools with least privilege and deterministic validation.
- Human approval and monitoring.
- AI remains advisory and cannot bypass authoritative controls.

## 35. V1 Flow Diagram

```text
Restaurant public ID
        |
        v
Resolve Restaurant and authorization context
        |
        v
Resolve ownership-scoped Inventory
        |
        v
Resolve Surplus Detection for that Inventory
        |
        +---- status is not an automatic safety or eligibility decision
        |
        v
Load typed facts with source references
        |
        v
Resolve one applicable authoritative policy version
        |
        +---- no approved policy --------------> REQUIRES_REVIEW
        |
        +---- equally applicable policies -----> REQUIRES_REVIEW
        |                                         (select none automatically)
        |
        v
Run deterministic rules
        |
        +---- blocking rule -------------------> NOT_ELIGIBLE
        |
        +---- missing/conflicting facts --------> REQUIRES_REVIEW
        |
        +---- all required rules pass ----------> ELIGIBLE_FOR_OFFER
        |
        v
Persist immutable evaluation + required audit rule results
        + selected policy version or policy-resolution reason
        |
        v
Return eligibility response
        |
        +---- no Inventory mutation
        +---- no food-safety certification
        +---- no automatic Offer creation
        |
        v
Future authorized Offer workflow may evaluate the eligible result
```

Invalid or overlapping policy bundles are rejected before publication or
activation and therefore do not enter this runtime flow.
