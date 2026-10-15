# Travel Invoice API

Java 21 / Spring Boot / PostgreSQL 17. Implements transaction Create, GET, PATCH, and DELETE, plus invoice Create, GET, PATCH, and DELETE.

With Docker running, copy `.env.example` to `.env` once, then run:

```sh
docker compose up --build
```

Run the integration tests with Java 21 and Docker available:

```sh
./gradlew test
```

Tests start their own PostgreSQL 17 container and apply Flyway migrations. They do not require `.env` or the development database.

Create a transaction using one of `flight`, `hotel`, `rail`, `trip_fee`, or `agent_call_fee` from the fixtures (examples use `jq`):

```sh
jq '.flight' fixtures/task-b/seed-payloads.json | curl -i -X POST http://localhost:8080/transactions -H 'Content-Type: application/json' --data-binary @-
curl -i http://localhost:8080/transactions/1
```

Successful creation returns 201 with the stored record and a Location header. Invalid input returns 400; missing records return 404. Repeating the valid POST returns 409 with `existing_id`, without modifying the original transaction. Money uses decimal values with up to two fractional digits. Taxes and fees are returned in ascending database-ID order.

Create an invoice from one transaction or a list of transaction IDs:

```sh
curl -i -X POST http://localhost:8080/invoices -H 'Content-Type: application/json' -d '{"transaction_id":1}'
curl -i -X POST http://localhost:8080/invoices -H 'Content-Type: application/json' -d '{"transaction_ids":[1,2,3,4,5]}'
curl -i http://localhost:8080/invoices/1
```

Invoice creation returns 201. Missing source transactions or invoices return 404. Mixed currencies and repeated transaction IDs return 409; invalid request shapes or an overflowing grand total return 400. Provide exactly one ID field. Snapshot lines are ordered by ascending source transaction ID. Taxes roll up by name/rate and fees by name; gross totals already include these charges. The five trip fixtures produce EUR 685.70.

Invoice GET reads stored snapshots, so later source changes do not alter billed data. List endpoints are not implemented in this phase.


## Transaction PATCH and DELETE

```sh
curl -i -X PATCH http://localhost:8080/transactions/1 -H 'Content-Type: application/json' -d '{"total":225.50}'
curl -i -X PATCH http://localhost:8080/transactions/1 -H 'Content-Type: application/json' -d '{"tax_lines":[],"fee_lines":[]}'
curl -i -X DELETE http://localhost:8080/transactions/1
```

PATCH supports `total`, `occurred_at`, `tax_lines`, `fee_lines`, and `metadata`. Omitted fields keep their current values. Explicit top-level nulls return 400, including `{"metadata":null}`; optional nested tax rates may still be null. Supplied tax/fee arrays replace the entire list, and empty arrays clear it. Metadata is a complete replacement, not a merge: include every required field for the stored transaction type. Invalid replacement metadata rejects the entire PATCH, including any other supplied changes. Monetary precision and metadata validation match transaction creation.

`external_id`, `type`, `currency`, and all unsupported fields cannot be patched; supplying them returns 400 even if their values match. An empty object is a no-op for an uninvoiced transaction. Successful PATCH returns 200 with the updated transaction; successful DELETE returns 204 without a body. Missing transactions return 404. Both operations return 409 when the transaction belongs to any invoice (including an empty PATCH), leaving transactions, taxes, fees, and invoice snapshots unchanged. To observe the conflict, create an invoice for a transaction, then repeat the PATCH or DELETE curl for that ID.

Each operation locks the transaction before checking invoice membership. Header changes and tax/fee replacement or deletion commit atomically. Concurrent operations serialize on the same transaction; no partial child lists are exposed by a committed write.


## Invoice PATCH and DELETE

```sh
curl -i -X PATCH http://localhost:8080/invoices/1 -H 'Content-Type: application/json' -d '{"transaction_ids":[2,3]}'
curl -i -X PATCH http://localhost:8080/invoices/1 -H 'Content-Type: application/json' -d '{"transaction_id":2}'
curl -i -X DELETE http://localhost:8080/invoices/1
```

Invoice PATCH replaces the complete transaction set and rebuilds every snapshot from current stored source data. It accepts the same body as invoice creation: provide exactly one of `transaction_id` or a nonempty `transaction_ids` list. The invoice ID and creation timestamp are preserved. Currency, grand total, snapshot lines, and tax/fee rollups are recalculated; replacing with an entirely different currency is allowed if all new sources agree. Successful PATCH returns 200. Missing invoice/source IDs return 404, mixed currencies or duplicate IDs return 409, and invalid request shapes return 400. Validation or database failures preserve the original invoice and all snapshot rows.

Invoice DELETE returns 204 and removes the invoice and its snapshots, preserving all source transactions, taxes, and fees. A missing invoice returns 404. After deletion or replacement commits, a removed source becomes editable/deletable unless another invoice still references it. Concurrent invoice and transaction operations are serialized through database row locks; a transaction mutation may still return 409 if it observes invoice membership before removal commits. Invoice GET fetches the header and snapshots together in one database statement to avoid combining data from different committed invoice versions.
