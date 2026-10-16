# Travel Invoice API

## 1. Overview

A backend service for structured travel transactions and invoice snapshots. Supports flight, hotel, rail, and Navan fee transactions (`trip_fee` and `agent_call_fee`), with separate taxes, fees, and type-specific metadata. Transactions and invoices support creation, retrieval, replacement/update, deletion, and paginated lists.

Invoices capture stored transaction data independently of later source changes. Explicit invoice PATCH rebuilds their snapshots; invoiced transactions cannot be modified or deleted.

## 2. Tech Stack

Java 21, Spring Boot 4.1.1, Spring Data JPA/Hibernate, PostgreSQL 17, Flyway, Gradle Kotlin DSL, Docker Compose, and PostgreSQL Testcontainers.

## 3. Prerequisites

- **Run the Docker stack:** Docker with Compose. The image builds the application; local Java and Gradle installations are unnecessary. Default host ports 8080 and 5432 must be available.
- **Run tests locally:** Java 21 and a running Docker daemon. Use the included Gradle wrapper; no separate Gradle installation is needed.
- **Run examples:** Bash, `curl` supporting `--fail-with-body`, and `jq`. Run commands from the repository root.

## 4. Quick Start

Create the local environment file once, preserving any existing settings:

```bash
if [ ! -f .env ]; then cp .env.example .env; fi
docker compose up --build
```

Compose starts PostgreSQL and the API, applies Flyway migrations, and validates the schema. Wait for the application startup message. The default API URL is `http://localhost:8080`. In another Bash shell, verify it responds:

```bash
BASE_URL="${BASE_URL:-http://localhost:8080}"
curl --fail-with-body --silent --show-error "$BASE_URL/transactions?limit=1" | jq .
```

A fresh database returns an empty `items` array. PostgreSQL data persists in a Docker volume across restarts.

## 5. Running Tests

```bash
./gradlew test
```

Tests start their own PostgreSQL 17 Testcontainer, apply Flyway migrations, and exercise the HTTP API on random ports. Neither the development database nor `.env` is required. Tests cover fixtures, validation, financial rollups, snapshots, rollback, concurrent operations, and pagination/query counts. Test classes run sequentially; concurrency scenarios coordinate requests explicitly.

## 6. API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/transactions` | Create a transaction; reject duplicate external IDs |
| GET | `/transactions/{id}` | Read a stored transaction |
| GET | `/transactions` | List complete transactions with pagination |
| PATCH | `/transactions/{id}` | Update supported fields if uninvoiced |
| DELETE | `/transactions/{id}` | Delete an uninvoiced transaction and its children |
| POST | `/invoices` | Snapshot one or more stored transactions |
| GET | `/invoices/{id}` | Read invoice snapshots and rollups |
| GET | `/invoices` | List complete invoices with pagination |
| PATCH | `/invoices/{id}` | Replace the source set and rebuild snapshots |
| DELETE | `/invoices/{id}` | Delete the invoice and snapshots, preserving sources |

Successful POST returns **201**, GET/PATCH **200**, and DELETE **204**. Invalid input returns **400**, missing resources **404**, and duplicate external IDs, duplicate invoice source IDs, mixed currencies, or mutation of invoiced transactions **409**. Errors use HTTP Problem Detail responses; duplicate creation includes `existing_id`.

## 7. Example Workflow

Run the following blocks sequentially in one Bash shell against a fresh database. Fixture creation is intended to run once: existing `external_id` values cause 409. Continue using that shell for the mutation and pagination examples.

Create all five transactions from [seed-payloads.json](fixtures/task-b/seed-payloads.json), capturing actual server-generated IDs:

```bash
BASE_URL="${BASE_URL:-http://localhost:8080}"
create_fixture() {
  jq --arg kind "$1" '.[$kind]' fixtures/task-b/seed-payloads.json |
    curl --fail-with-body --silent --show-error "$BASE_URL/transactions" \
      -H 'Content-Type: application/json' --data-binary @- |
    jq -er '.id'
}
flight_id=$(create_fixture flight)
hotel_id=$(create_fixture hotel)
rail_id=$(create_fixture rail)
trip_fee_id=$(create_fixture trip_fee)
agent_call_fee_id=$(create_fixture agent_call_fee)
curl --fail-with-body --silent --show-error "$BASE_URL/transactions/$flight_id" | jq .
```

Each create returns 201. The GET returns stored metadata, taxes, and fees. Monetary amounts support up to two fractional digits; currency codes must be valid uppercase three-letter codes.

Repeat the flight POST: expect **409** and `existing_id` equal to `$flight_id`:

```bash
jq '.flight' fixtures/task-b/seed-payloads.json |
  curl --silent --show-error -w '\nHTTP %{http_code}\n' "$BASE_URL/transactions" \
    -H 'Content-Type: application/json' --data-binary @-
printf 'Expected existing_id: %s\n' "$flight_id"
```

Prove that only one transaction was persisted using the Compose database:

```bash
docker compose exec -T postgres sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
SELECT external_id, count(*) AS stored_rows
FROM transactions
WHERE external_id = 'flight-TXL-LHR-2026-03-11'
GROUP BY external_id;
SQL
```

Expected `stored_rows`: **1**. Duplicate POST does not overwrite the existing transaction.

Create and retrieve the trip invoice using the returned IDs:

```bash
invoice=$(jq -n --argjson flight "$flight_id" --argjson hotel "$hotel_id" \
  --argjson rail "$rail_id" --argjson trip_fee "$trip_fee_id" \
  --argjson agent_call_fee "$agent_call_fee_id" \
  '{transaction_ids:[$flight,$hotel,$rail,$trip_fee,$agent_call_fee]}' |
  curl --fail-with-body --silent --show-error "$BASE_URL/invoices" \
    -H 'Content-Type: application/json' --data-binary @-)
invoice_id=$(printf '%s' "$invoice" | jq -er '.id')
printf '%s' "$invoice" | jq '{id,currency,grand_total,tax_rollup,fee_rollup}'
curl --fail-with-body --silent --show-error "$BASE_URL/invoices/$invoice_id" | jq .
```

Expected values match [expected-invoice.json](fixtures/task-b/expected-invoice.json):

| Field | Expected value |
|---|---|
| Currency / grand total | EUR / **685.70** |
| Tax rollup | Airport tax **15.00**; VAT at **0.07**, amount **21.70** |
| Fee rollup | Booking fee **4.00**; YQ **25.00** |

Grand total sums gross transaction totals once; taxes and fees are not added again. A single-source invoice can use `transaction_id` instead of `transaction_ids`. Supply exactly one field and a nonempty source set. Positive integer JSON IDs and integer strings are supported; fractional values (including `1.0`), invalid types, and out-of-range IDs return 400. Missing sources return 404; mixed currencies or repeated IDs return 409.

## 8. PATCH and DELETE Examples

### Transactions

Use a separate uninvoiced transaction for successful mutations, then verify that the trip flight is protected:

```bash
# Create a separate uninvoiced record to demonstrate successful PATCH and DELETE.
scratch_id=$(jq '.flight | .external_id += "-crud-example"' fixtures/task-b/seed-payloads.json |
  curl --fail-with-body --silent --show-error "$BASE_URL/transactions" \
    -H 'Content-Type: application/json' --data-binary @- | jq -er '.id')
curl -i -X PATCH "$BASE_URL/transactions/$scratch_id" -H 'Content-Type: application/json' -d '{"total":225.50}'
curl -i -X PATCH "$BASE_URL/transactions/$scratch_id" -H 'Content-Type: application/json' -d '{"tax_lines":[],"fee_lines":[]}'
curl -i -X DELETE "$BASE_URL/transactions/$scratch_id"
# The trip flight is invoiced: both commands below must return 409.
curl -i -X PATCH "$BASE_URL/transactions/$flight_id" -H 'Content-Type: application/json' -d '{"total":225.50}'
curl -i -X DELETE "$BASE_URL/transactions/$flight_id"
```

PATCH supports `total`, `occurred_at`, `tax_lines`, `fee_lines`, and `metadata`. Omitted fields retain their values; explicit top-level nulls return 400. Optional nested tax rates may be null. Tax/fee arrays and metadata are complete replacements, not merges; empty arrays clear collections. Replacement metadata must include all required fields for the stored type. Invalid input rejects the entire update.

`external_id`, `type`, `currency`, and unsupported fields cannot be patched. An empty PATCH is a no-op only for an uninvoiced transaction. If any invoice references the transaction, PATCH (including an empty object) and DELETE return 409 without changing stored data.

### Invoices

Replace the trip invoice with two sources, replace it again with one, then delete it:

```bash
jq -n --argjson flight "$flight_id" --argjson hotel "$hotel_id" \
  '{transaction_ids:[$flight,$hotel]}' |
  curl -i -X PATCH "$BASE_URL/invoices/$invoice_id" -H 'Content-Type: application/json' --data-binary @-
jq -n --argjson flight "$flight_id" '{transaction_id:$flight}' |
  curl -i -X PATCH "$BASE_URL/invoices/$invoice_id" -H 'Content-Type: application/json' --data-binary @-
curl -i -X DELETE "$BASE_URL/invoices/$invoice_id"
```

PATCH uses the same request format as invoice creation. It atomically rebuilds all snapshots, currency, total, and rollups from current stored sources, preserving invoice ID and creation time. Failures leave the original invoice unchanged. All replacement sources must share a currency, which may differ from the previous invoice currency.

DELETE removes only the invoice and snapshots. Removed sources become editable/deletable after commit unless another invoice still references them. Source transactions, taxes, and fees remain stored.

## 9. Pagination

```bash
curl -i "$BASE_URL/transactions?limit=50&offset=0"
curl -i "$BASE_URL/invoices?limit=100&offset=100"
```

Both endpoints accept `limit` (default **50**, range **1–100**) and `offset` (default **0**, nonnegative). Invalid bounds/types or values outside the Java integer range return 400.

Responses have the form `{"items":[...],"limit":50,"offset":0}`. Items appear in ascending ID order and contain complete single-resource DTOs, including nested data and invoice rollups. Empty pages return 200 with `items: []`; no total count is included. The invoice example above may return an empty page after the workflow deletes its only invoice.

See [Pagination & Performance](ARCHITECTURE.md#6-pagination--performance) for database-side pagination, N+1 prevention, read isolation, measured query counts, and OFFSET limitations.

## 10. Database Indexes

Flyway creates the schema and indexes; Hibernate validates them:

- Primary keys on transaction/invoice IDs support resource lookup and ascending-ID pagination.
- `transactions_external_id_key` enforces uniqueness under concurrent POSTs.
- `idx_transaction_taxes_transaction_id` and `idx_transaction_fees_transaction_id` support child loading.
- `uq_invoice_transaction` prevents repeated sources per invoice and supports snapshot lookup by its leading `invoice_id`.
- `idx_snapshot_transaction` supports the invoice-membership check before transaction mutation.

## 11. Architecture & Design Decisions

See [ARCHITECTURE.md](ARCHITECTURE.md) for snapshot independence, atomic writes, row-lock ordering and race handling, PostgreSQL-specific duplicate resolution, and bounded fetching/performance trade-offs. The design uses the database for identity and concurrency control without additional infrastructure.
