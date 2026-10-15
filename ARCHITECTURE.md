## Data Validation and Modeling Decisions

### Currency Validation

Currency values are stored as three-character codes in PostgreSQL. The application validates currency codes before persistence using Java's Currency API. This keeps invalid currency values from entering through the HTTP API.

### Tax and Fee Ordering

Transaction taxes and fees are stored in separate relational tables. Their original input order is not guaranteed by the current schema. The API returns them in a deterministic order based on their database IDs. Original array ordering is not part of the API contract.

### Type-Specific Metadata Validation

Transaction-specific metadata is stored as PostgreSQL JSONB to support different travel products without introducing separate tables for every transaction type.

The application validates metadata according to the transaction type before persistence. Required flight, hotel, rail, and Navan fee fields are checked, including supported Navan fee kinds. Invalid metadata results in HTTP 400.

This approach keeps the database schema simple while maintaining validation at the API boundary.

### Transaction Create and GET

`Transaction` is the aggregate root. Taxes and fees are separate child entities with non-null foreign keys, cascading persistence, and `@OrderBy("id ASC")`. DTOs define the HTTP contract; JPA entities are never returned by controllers. Headers and children are persisted in one database transaction. Reads map the aggregate to a DTO inside a read-only transaction because Open Session in View is disabled.

Java enum constants are uppercase; JSON and database values remain `flight`, `hotel`, `rail`, and `navan_fee`. Metadata is a Jackson JSON tree mapped with Hibernate's Jackson 3 JSONB support. Decimal JSON numbers are parsed as BigDecimal, preserving precision and additional metadata fields. JSONB preserves values and array order, not textual object-key order or number formatting. Flight `coupons` must exist as an array but may be empty; every supplied coupon is validated. Rail `legs` must be nonempty.

Money columns accept at most 17 integer digits and two fractional digits; tax rates accept up to six fractional digits and range from zero to one. Excess precision is rejected instead of rounded. Totals are already gross amounts, so taxes and fees are not added to them again. Currency validation uses `Currency.getInstance()` in addition to checking uppercase three-letter syntax.

### Duplicate Identity and Concurrent Creates

The existing unique constraint `transactions_external_id_key` is the source of truth. Valid duplicate POSTs return HTTP 409 with `existing_id`; they never replace stored fields or child records. Requests are validated before insertion, including duplicate requests.

The service uses a `TransactionTemplate` for the entire aggregate write. A unique-constraint violation escapes that transaction, which rolls back before conflict recovery begins. The service then opens a fresh read-only transaction to look up the existing ID. PostgreSQL's unique constraint serializes competing inserts for the same external ID; the loser reads the winner's committed record. Other database errors are not reported as duplicate conflicts.

### Integration Tests

`./gradlew test` starts an isolated PostgreSQL 17 Testcontainer, applies the unchanged Flyway migrations, and starts the HTTP API on a random port with Hibernate schema validation enabled. Tests cover all five seed cases, decimal and metadata preservation, required/type-specific validation, deterministic child ordering, missing resources, sequential and concurrent duplicates, and atomic rollback after a forced child-insert failure. Docker must be running. Tests do not use the development database.
## Transaction Management and Trade-offs

### Transaction Propagation

Transaction creation uses Spring's `TransactionTemplate` to manage database transactions explicitly.

When a duplicate `external_id` causes a PostgreSQL unique constraint violation, the write transaction is rolled back before a separate read transaction retrieves the existing transaction ID.

Both transaction templates currently use the default `PROPAGATION_REQUIRED` behavior.

**Trade-off:** This approach works correctly for the current HTTP endpoint because no outer transaction exists. However, if the service is called from another method with an active `@Transactional` context, the templates may join that transaction, breaking the assumption that rollback completes before duplicate recovery.

**Decision:** Keep the current implementation for the assignment. If transaction composition is introduced later, revisit the propagation strategy and transaction boundaries. `REQUIRES_NEW` is an option, but its independent commit behavior must be considered carefully.

### Database Constraint Name Dependency

Duplicate detection relies on PostgreSQL SQLSTATE `23505` (unique constraint violation) and the constraint name `transactions_external_id_key`.

Checking both ensures that unrelated database integrity errors are not incorrectly treated as duplicate transactions.

**Trade-off:** The implementation depends on the database constraint name. Renaming the constraint in a future Flyway migration without updating the application would break duplicate conflict detection.

**Decision:** Keep the current implementation because the constraint is explicitly controlled by the project's database schema. Any future migration that renames it must update the corresponding application logic and tests.

### Concurrency Verification

Concurrent duplicate creation is verified through HTTP integration tests against PostgreSQL 17 using Testcontainers.

The tests confirm that two overlapping requests with the same `external_id` result in:

- One successful creation (`201 Created`).
- One duplicate response (`409 Conflict`) containing the existing transaction ID.
- Exactly one stored transaction.
- No duplicate tax or fee records.
- No modification of the original transaction data.

At the time of verification, 50 concurrent race-test executions passed without failures. This provides evidence of correctness under the tested conditions but does not guarantee that every possible concurrency schedule has been exercised.

### Invoice Create and GET

An invoice owns ordered snapshot lines in `invoice_line_snapshots`. Each line copies source transaction ID, type, currency, gross total, metadata, tax lines, and fee lines. The source ID is a scalar JPA field; PostgreSQL retains its existing foreign key, but invoice reads have no entity relationship to current transactions. Metadata is recursively deep-copied, tax/fee lists and their immutable value records are copied, and metadata getters return defensive copies. The existing Hibernate JSON configuration handles all snapshot JSONB fields with decimal precision preserved.

Create accepts exactly one of `transaction_id` or `transaction_ids`. Empty/null/nonpositive IDs and invalid request shapes return 400; repeated IDs return 409. All sources must exist (404) and share one currency (409). Validation, source loading, snapshot copying, and invoice/header persistence happen within one database transaction. Any failure rolls back the header and every snapshot line. An aggregate outside NUMERIC(19,2) bounds is rejected with 400 before persistence.

Grand total sums source gross totals once, using BigDecimal; tax and fee amounts are not added again. Tax rollups group exact, case-sensitive names and numerically equivalent rates (trailing zeros ignored), keeping an absent rate distinct from zero. Fee rollups group exact names. Rollups sort alphabetically by name, then rate with absent rate first. They are derived exclusively from persisted snapshot values, including on GET. Snapshot lines sort by database line ID; creation inserts them in ascending source ID order. No live transaction joins or new lifecycle statuses are used.

### Lock Ordering for Invoice and Future Mutation Operations

Invoice creation sorts unique source IDs and acquires `PESSIMISTIC_WRITE` locks individually in ascending order through `TransactionRepository.findByIdForUpdate`. All header locks are acquired before loading tax/fee collections, and remain held until invoice commit or rollback. One query per source makes lock acquisition order explicit; it favors simplicity over bulk-query throughput.

Transaction PATCH/DELETE acquires the same parent transaction lock before checking invoice membership and before changing the header or any tax/fee children. An invoiced transaction rejects those operations with 409. Direct SQL writes to children that bypass this parent-lock protocol are outside that coordination contract.

Future invoice PATCH must first lock the invoice header, then lock the union of existing and replacement transaction IDs in ascending order, and atomically rebuild its snapshots. If invoice DELETE locks source rows to coordinate membership removal, it must follow the same invoice-first, ascending-source order. Never acquire an existing invoice lock after holding source transaction locks. If a future operation locks multiple invoices, acquire those invoice locks in ascending ID order first. Invoice create has no pre-existing invoice to lock. This common ordering prevents lock cycles among compliant application operations; long-held locks and unrelated SQL can still cause waits or database timeouts.

### Invoice Verification

Sequential PostgreSQL 17 Testcontainers HTTP tests verify single/trip invoices (EUR 685.70), rollup grouping and decimal sums, error statuses and empty rollback results, full source header/metadata/tax/fee mutation independence, a forced failure after an earlier snapshot line was inserted, and reversed-order concurrent invoice requests. A database lock-contention test holds the higher source ID, proves the lower ID is already locked by the blocked HTTP request using `FOR UPDATE NOWAIT`, then releases the blocker and verifies successful completion. Existing transaction endpoint behavior remains unchanged.


### Transaction PATCH Semantics and Atomic Mutation

PATCH uses a dedicated presence-aware DTO backed by a copied JSON object. An omitted key and an explicitly null key are distinct: omission preserves the current value, while explicit null is rejected with 400. A whitelist rejects identity changes (`external_id`, `type`, `currency`) and unsupported fields. Metadata and tax/fee arrays are complete replacements, not recursive merges. Empty arrays clear children, while an empty PATCH is a no-op only for an uninvoiced transaction.

After acquiring the existing `PESSIMISTIC_WRITE` parent lock, the service checks invoice membership using an indexed `existsByTransactionId` query. Missing rows yield 404, and any billed transaction yields 409. It then merges supplied top-level fields with the locked current state and validates the complete proposed state using the existing create DTO constraints, Java Currency validation, and type-specific metadata validator. No entity mutation happens until validation completes. Mutable JSON is deep-copied when applied. Only supplied collections are replaced, preserving omitted child identities; managed collections use orphan removal and correct parent references.

PATCH and DELETE each use one write TransactionTemplate, retaining the parent lock through commit or rollback. DELETE removes the aggregate and its children atomically. No invoice header locks are acquired by these operations. Transaction create's existing duplicate-recovery boundaries are unchanged.

Concurrent PATCH/PATCH requests read current values after acquiring the lock, so disjoint supplied fields survive both requests; overlapping changes use the last committed write. PATCH/DELETE yields either PATCH 200 followed by DELETE 204 or DELETE 204 followed by PATCH 404. If invoice creation commits first, later transaction mutation returns 409. If PATCH wins, the invoice snapshots the complete updated aggregate; if DELETE wins, invoice creation returns 404 without partial invoice records. Snapshot rows are never rewritten by transaction mutation.

HTTP integration tests run sequentially against PostgreSQL 17 Testcontainers. They separately verify omission and explicit null, full metadata replacement and invalid partial metadata, field/list validation, no-op and clear-list behavior, invoice conflicts, successful deletion, and rollback after forced fee-insert or header-delete failure. Concurrent mutation tests repeat races and use test-only triggers/advisory gates to force both invoice-first and mutation-first schedules while confirming row-lock waits through PostgreSQL. These gates are confined to test databases; production uses only the existing row-level locking protocol. Raw SQL writers must follow that protocol too. Long-running transactions may still cause lock waits/timeouts; this phase adds no lock timeout policy or retry mechanism.
