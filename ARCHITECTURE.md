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

Invoice PATCH first locks the invoice header, then locks the union of existing and replacement transaction IDs in ascending order, and atomically rebuilds its snapshots. Invoice DELETE also locks its source rows, following the same invoice-first, ascending-source order. Never acquire an existing invoice lock after holding source transaction locks. If a future operation locks multiple invoices, acquire those invoice locks in ascending ID order first. Invoice create has no pre-existing invoice to lock. This common ordering prevents lock cycles among compliant application operations; long-held locks and unrelated SQL can still cause waits or database timeouts.

### Invoice Verification

Sequential PostgreSQL 17 Testcontainers HTTP tests verify single/trip invoices (EUR 685.70), rollup grouping and decimal sums, error statuses and empty rollback results, full source header/metadata/tax/fee mutation independence, a forced failure after an earlier snapshot line was inserted, and reversed-order concurrent invoice requests. A database lock-contention test holds the higher source ID, proves the lower ID is already locked by the blocked HTTP request using `FOR UPDATE NOWAIT`, then releases the blocker and verifies successful completion. Existing transaction endpoint behavior remains unchanged.


### Transaction PATCH Semantics and Atomic Mutation

PATCH uses a dedicated presence-aware DTO backed by a copied JSON object. An omitted key and an explicitly null key are distinct: omission preserves the current value, while explicit null is rejected with 400. A whitelist rejects identity changes (`external_id`, `type`, `currency`) and unsupported fields. Metadata and tax/fee arrays are complete replacements, not recursive merges. Empty arrays clear children, while an empty PATCH is a no-op only for an uninvoiced transaction.

After acquiring the existing `PESSIMISTIC_WRITE` parent lock, the service checks invoice membership using an indexed `existsByTransactionId` query. Missing rows yield 404, and any billed transaction yields 409. It then merges supplied top-level fields with the locked current state and validates the complete proposed state using the existing create DTO constraints, Java Currency validation, and type-specific metadata validator. No entity mutation happens until validation completes. Mutable JSON is deep-copied when applied. Only supplied collections are replaced, preserving omitted child identities; managed collections use orphan removal and correct parent references.

PATCH and DELETE each use one write TransactionTemplate, retaining the parent lock through commit or rollback. DELETE removes the aggregate and its children atomically. No invoice header locks are acquired by these operations. Transaction create's existing duplicate-recovery boundaries are unchanged.

Concurrent PATCH/PATCH requests read current values after acquiring the lock, so disjoint supplied fields survive both requests; overlapping changes use the last committed write. PATCH/DELETE yields either PATCH 200 followed by DELETE 204 or DELETE 204 followed by PATCH 404. If invoice creation commits first, later transaction mutation returns 409. If PATCH wins, the invoice snapshots the complete updated aggregate; if DELETE wins, invoice creation returns 404 without partial invoice records. Snapshot rows are never rewritten by transaction mutation.

HTTP integration tests run sequentially against PostgreSQL 17 Testcontainers. They separately verify omission and explicit null, full metadata replacement and invalid partial metadata, field/list validation, no-op and clear-list behavior, invoice conflicts, successful deletion, and rollback after forced fee-insert or header-delete failure. Concurrent mutation tests repeat races and use test-only triggers/advisory gates to force both invoice-first and mutation-first schedules while confirming row-lock waits through PostgreSQL. These gates are confined to test databases; production uses only the existing row-level locking protocol. Raw SQL writers must follow that protocol too. Long-running transactions may still cause lock waits/timeouts; this phase adds no lock timeout policy or retry mechanism.


### Invoice Replacement and Deletion

PATCH reuses the create request format and snapshot-copy/validation logic. It locks the invoice header with PESSIMISTIC_WRITE, then locks the union of old and requested source IDs individually in ascending order. The replacement data is validated and deep-copied before any existing state changes. Removing all old lines from the managed collection schedules orphan deletion; an explicit repository flush physically deletes those rows before any new line is attached or persisted. This prevents Hibernate identity-generation insert ordering from violating the existing `(invoice_id, transaction_id)` unique constraint for overlapping sets. A PostgreSQL BEFORE INSERT test trigger checks that every original snapshot ID is absent at replacement-insert time.

After the deletion flush, currency and grand total are updated and fresh snapshots are inserted, all inside the same @Transactional service call. Flush is not commit: forced snapshot insert, header update, and invoice delete failures are tested to restore the exact original header and snapshot rows, including their IDs. Database sequences may still consume IDs on failed operations, as normal for PostgreSQL. Rollups are recalculated from the new frozen values using BigDecimal, with no extra addition of taxes/fees to gross totals. Invoice identity/creation timestamp remain unchanged. Reads use a fetch graph to retrieve the invoice and all lines in one SQL statement, avoiding an old header/new lines combination if replacement commits during GET.

DELETE locks the invoice first, then all currently referenced source transactions in ascending order, before deleting snapshot children and the invoice. Scalar snapshot transaction IDs mean there is no JPA cascade from snapshots to source transactions; the existing foreign keys remain unchanged. Both operations hold locks until commit/rollback. Only committed snapshot membership determines whether transaction PATCH/DELETE is allowed; another invoice can keep a source protected after one reference is removed.

### Foreign Key Locks and Concurrent Invoice Operations

Explicit lock ordering is necessary but is not treated as a complete proof against every PostgreSQL deadlock. Snapshot inserts also perform foreign-key checks against both source transactions and their invoice. For these operations, the referenced rows have already been locked by the same database transaction before snapshot insertion. Snapshot deletions and cascades are issued only after all source locks are acquired. Invoice operations never reach back to acquire another existing invoice lock while holding sources. Transaction mutation locks sources only and performs a non-locking membership existence query, so it does not wait for an invoice row while blocking that invoice's source acquisition.

Controlled PostgreSQL HTTP tests queue both invoice-first and transaction-first operations behind real source locks. Invoice replacement/deletion versus source mutation accepts outcomes according to the observed serialization: source mutation may reject old membership with 409, or succeed after removal commits. Adding a source races with mutation too: an earlier delete yields invoice 404 and unchanged old snapshots; earlier attachment yields source 409. Source PATCH either precedes snapshot creation completely or is rejected after attachment. Additional tests pause invoice DELETE during snapshot deletion and verify that waiting source mutations resume only after removal commits. Repeated same-invoice PATCH/PATCH and PATCH/DELETE, different-invoice source swaps, and invoice create versus delete exercise row locks, uniqueness, FK checks, and cascades together. No deadlock retry policy or new database objects are introduced in production; external writers and long lock holds remain operational risks.


### High volume: Pagination and Fetching

GET /transactions and GET /invoices accept integer limit (default 50, range 1–100) and offset (default 0, nonnegative). Both return `items`, `limit`, and `offset`, without an expensive total-count query. Invalid bounds/types return 400; empty pages return 200. The first native query selects only parent IDs ordered ascending with PostgreSQL LIMIT/OFFSET, using the existing primary-key index. Arbitrary offsets are supported directly, without rounding to page numbers or application-side slicing.

For a nonempty transaction page, two additional JPQL queries LEFT JOIN FETCH taxes and fees separately for only those parent IDs. Both use the same persistence context, so the second query initializes fees on the same parents whose taxes were loaded by the first. This avoids multiple-bag fetch errors and the taxes-times-fees Cartesian product. There is no LIMIT/OFFSET on either collection query. A map deduplicates fetch results, and DTOs are constructed by iterating the initial ID list rather than relying on IN-clause or join ordering. Existing @OrderBy annotations govern children. TransactionResponse.from() runs only after both collections are initialized.

For a nonempty invoice page, one additional query fetches the selected invoices and their snapshot lines. The same ID-to-parent mapping preserves exact initial order and one DTO per selected parent. Existing invoice response mapping recalculates rollups exclusively from persisted snapshots. Scalar snapshot source IDs prevent loading current transaction entities. Existing child indexes support bounded lookups: transaction tax/fee foreign-key indexes, and the leading invoice_id of the unique invoice snapshot index. Flyway migrations and JSONB mappings remain unchanged.

The entire list operation, including mapping, runs inside a read-only REPEATABLE_READ transaction so ID selection and subsequent collection reads see one coherent MVCC snapshot. This isolation is scoped only to pagination: InvoiceService.list uses its own annotation and TransactionService.list a dedicated TransactionTemplate. Existing read/write templates, single-resource reads, and mutation locks/isolation are unchanged. List reads acquire no pessimistic row locks. Long-lived MVCC snapshots can delay cleanup of old row versions, so list transactions remain short.

Expected prepared-statement counts through full HTTP response mapping are 3 for transactions and 2 for invoices, independent of nonempty page size (tested at 1, 50, and 100), and 1 for an empty page. Test-only Hibernate statistics and StatementInspector capture verify query counts, exact parent/child load counts, SQL LIMIT/OFFSET on the ID query, and absence of limits on fetch joins. Multiple tax/fee rows per source and two snapshots per invoice exercise completeness, deduplication, and absence of Cartesian multiplication. Tests fail if Hibernate attempts pagination over a collection fetch. Coordinated concurrent SQL writes between ID selection and child loading verify the repeatable-read consistency boundary for both endpoints.

OFFSET pagination gets more expensive for deep offsets because earlier index entries must still be traversed. Separate HTTP requests see separate snapshots, so concurrent deletions/inserts can shift page boundaries; ascending order guarantees stable coverage only when the underlying set is stable. A future cursor API could improve deep scans. Each page is bounded by parent count, not nested payload size; fetch joins also repeat header/JSON data for each child row. These trade-offs favor a small, explicit fixed-query implementation over global batching or broader projection infrastructure.

Under concurrent load, hot transaction/invoice row locks serialize conflicting writes, duplicate POSTs contend on the external_id unique index, and large/deep list reads consume database I/O and connections. Existing constraints prevent duplicate identities, mutation locks preserve financial aggregates, and bounded page sizes plus fixed-query collection loading avoid unbounded parent loads and N+1 amplification. No new infrastructure or configurable filtering/sorting is introduced.
