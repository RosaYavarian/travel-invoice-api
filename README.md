# Travel Invoice API

Java 21 / Spring Boot / PostgreSQL 17. Currently implements transaction Create and GET.

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

Invoices, PATCH, DELETE, and list endpoints are not implemented in this phase.
