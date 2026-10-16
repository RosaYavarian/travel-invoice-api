package com.navan.travelinvoice.pagination;

import com.navan.travelinvoice.model.entity.invoice.Invoice;
import com.navan.travelinvoice.model.entity.invoice.InvoiceLineSnapshot;
import com.navan.travelinvoice.model.entity.transaction.Transaction;
import com.navan.travelinvoice.model.entity.transaction.TransactionTax;
import com.navan.travelinvoice.model.entity.transaction.TransactionFee;
import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

@Import(PaginationIntegrationTest.SqlConfiguration.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true")
class PaginationIntegrationTest extends PostgresIntegrationTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    static class SqlConfiguration {
        @Bean
        SqlCapture sqlCapture() { return new SqlCapture(); }

        @Bean
        HibernatePropertiesCustomizer captureSql(SqlCapture capture) {
            return properties -> {
                properties.put("hibernate.session_factory.statement_inspector", capture);
                properties.put("hibernate.generate_statistics", true);
            };
        }
    }

    static class SqlCapture implements StatementInspector {
        final List<String> statements = new CopyOnWriteArrayList<>();
        volatile Runnable beforeCollectionFetch;
        @Override
        public String inspect(String sql) {
            String normalized = sql.toLowerCase().replaceAll("\\s+", " ");
            statements.add(normalized);
            Runnable hook = beforeCollectionFetch;
            if (hook != null && normalized.contains(" join ")) {
                beforeCollectionFetch = null;
                hook.run();
            }
            return sql;
        }
    }

    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    SqlCapture capture;

    @BeforeEach
    void seedVolume() {
        String metadata = payload("flight").get("metadata").toString();
        jdbc.update("INSERT INTO transactions (id, external_id, type, currency, total, occurred_at, metadata) SELECT n * 3, 'page-' || n, 'flight', 'EUR', 123.45, '2026-03-11T06:40:00Z'::timestamptz, ?::jsonb FROM generate_series(1,125) n", metadata);
        jdbc.execute("INSERT INTO transaction_taxes (transaction_id, name, rate, amount) SELECT t.id, x.name, x.rate, x.amount FROM transactions t CROSS JOIN (VALUES ('Tax A', NULL::numeric, 1.11), ('Tax B', 0.07, 2.22)) x(name,rate,amount)");
        jdbc.execute("INSERT INTO transaction_fees (transaction_id, name, amount) SELECT t.id, x.name, x.amount FROM transactions t CROSS JOIN (VALUES ('Fee A', 0.10), ('Fee B', 0.20), ('Fee C', 0.30)) x(name,amount)");
        jdbc.execute("INSERT INTO invoices (id, currency, grand_total) SELECT n * 5, 'EUR', 246.90 FROM generate_series(1,125) n");
        jdbc.execute("""
                INSERT INTO invoice_line_snapshots (invoice_id, transaction_id, type, currency, total, metadata, tax_lines, fee_lines)
                SELECT i.id, t.id, t.type, t.currency, t.total, t.metadata,
                  (SELECT jsonb_agg(jsonb_strip_nulls(jsonb_build_object('name',x.name,'rate',x.rate,'amount',x.amount)) ORDER BY x.id) FROM transaction_taxes x WHERE x.transaction_id=t.id),
                  (SELECT jsonb_agg(jsonb_build_object('name',x.name,'amount',x.amount) ORDER BY x.id) FROM transaction_fees x WHERE x.transaction_id=t.id)
                FROM invoices i JOIN transactions t ON t.id IN ((i.id/5)*3, (((i.id/5)%125)+1)*3)
                ORDER BY i.id,t.id
                """);
        clearMeasurements();
    }

    @ParameterizedTest
    @CsvSource({"transactions,1,3", "transactions,50,3", "transactions,100,3", "invoices,1,2", "invoices,50,2", "invoices,100,2"})
    void completeHttpOperationHasConstantQueryCountAndOnlyLoadsSelectedRecords(String endpoint, int limit, int queries) throws Exception {
        int offset = 7;
        JsonNode page = page(endpoint, "?limit=" + limit + "&offset=" + offset);
        assertThat(page.get("limit").intValue()).isEqualTo(limit);
        assertThat(page.get("offset").intValue()).isEqualTo(offset);
        assertThat(ids(page)).containsExactlyElementsOf(expectedIds(endpoint, offset, limit));
        assertComplete(page, endpoint);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(queries);
        assertThat(capture.statements).hasSize(queries);
        assertBoundedSql(endpoint);
        assertThat(statistics().getCollectionFetchCount()).isZero();
        assertThat(statistics().getEntityFetchCount()).isZero();
        if (endpoint.equals("transactions")) {
            assertLoads(Transaction.class, limit);
            assertLoads(TransactionTax.class, 2L * limit);
            assertLoads(TransactionFee.class, 3L * limit);
            assertLoads(Invoice.class, 0);
            assertThat(capture.statements.subList(1, 3)).allSatisfy(sql -> {
                assertThat(sql.contains("transaction_taxes") ^ sql.contains("transaction_fees")).isTrue();
            });
        } else {
            assertLoads(Invoice.class, limit);
            assertLoads(InvoiceLineSnapshot.class, 2L * limit);
            assertLoads(Transaction.class, 0);
            assertLoads(TransactionTax.class, 0);
            assertLoads(TransactionFee.class, 0);
            assertThat(capture.statements).noneMatch(sql -> sql.contains("from transactions") || sql.contains("join transactions"));
        }
        System.out.printf("%s limit=%d offset=%d: %d statements through HTTP response mapping%n",
                endpoint, limit, offset, statistics().getPrepareStatementCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "invoices"})
    void defaultPageIs50AndMaximumPageIs100(String endpoint) throws Exception {
        JsonNode defaults = page(endpoint, "");
        assertThat(defaults.get("limit").intValue()).isEqualTo(50);
        assertThat(defaults.get("offset").intValue()).isZero();
        assertThat(ids(defaults)).containsExactlyElementsOf(expectedIds(endpoint, 0, 50));
        assertComplete(defaults, endpoint);
        JsonNode maximum = page(endpoint, "?limit=100");
        assertThat(ids(maximum)).containsExactlyElementsOf(expectedIds(endpoint, 0, 100));
    }

    @ParameterizedTest
    @CsvSource({"transactions,limit=1000", "transactions,limit=101", "transactions,limit=0", "transactions,limit=-1", "transactions,limit=abc", "transactions,limit=1.5", "transactions,offset=-1", "transactions,offset=abc", "transactions,offset=2147483648",
            "invoices,limit=1000", "invoices,limit=101", "invoices,limit=0", "invoices,limit=-1", "invoices,limit=abc", "invoices,limit=1.5", "invoices,offset=-1", "invoices,offset=abc", "invoices,offset=2147483648"})
    void invalidPaginationReturns400WithoutQueries(String endpoint, String query) throws Exception {
        assertThat(get("/" + endpoint + "?" + query).statusCode()).isEqualTo(400);
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "invoices"})
    void stablePagesCoverMoreThan100ParentsExactlyOnce(String endpoint) throws Exception {
        List<Long> seen = new ArrayList<>();
        for (int offset = 0; offset < 125; offset += 37) {
            JsonNode page = page(endpoint, "?limit=37&offset=" + offset);
            assertComplete(page, endpoint);
            seen.addAll(ids(page));
        }
        assertThat(seen).containsExactlyElementsOf(expectedIds(endpoint, 0, 125)).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "invoices"})
    void emptyPageOnlyExecutesBoundedIdQuery(String endpoint) throws Exception {
        JsonNode page = page(endpoint, "?limit=50&offset=9999");
        assertThat(page.get("items").isEmpty()).isTrue();
        assertThat(page.get("offset").intValue()).isEqualTo(9999);
        assertThat(page.get("limit").intValue()).isEqualTo(50);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics().getEntityLoadCount()).isZero();
        assertBoundedSql(endpoint);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "invoices"})
    void emptyDatabaseReturnsEmptyDefaultPage(String endpoint) throws Exception {
        jdbc.execute("TRUNCATE TABLE invoices,transactions RESTART IDENTITY CASCADE");
        JsonNode page = page(endpoint, "");
        assertThat(page.get("items").isEmpty()).isTrue();
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=1", "?limit=50", "?limit=100"})
    void invoicePagesRemainSnapshotsAfterLiveSourceMutation(String query) throws Exception {
        jdbc.execute("UPDATE transactions SET total=999.99,metadata='{\"changed\":true}'::jsonb");
        jdbc.execute("UPDATE transaction_taxes SET amount=99.99,name='Changed tax'");
        jdbc.execute("UPDATE transaction_fees SET amount=99.99,name='Changed fee'");
        JsonNode page = page("invoices", query);
        assertComplete(page, "invoices");
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2);
        assertLoads(Transaction.class, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "invoices"})
    void idPageAndChildrenUseOneConsistentDatabaseSnapshot(String endpoint) throws Exception {
        CountDownLatch idsSelected = new CountDownLatch(1);
        CountDownLatch changesCommitted = new CountDownLatch(1);
        capture.beforeCollectionFetch = () -> {
            idsSelected.countDown();
            try {
                if (!changesCommitted.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer did not finish");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var request = executor.submit(() -> page(endpoint, "?limit=1"));
            try {
                assertThat(idsSelected.await(10, TimeUnit.SECONDS)).isTrue();
                if (endpoint.equals("transactions")) {
                    jdbc.execute("UPDATE transactions SET total=999.99, metadata='{\"changed\":true}'::jsonb");
                    jdbc.execute("UPDATE transaction_taxes SET amount=99.99,name='Changed tax'");
                    jdbc.execute("UPDATE transaction_fees SET amount=99.99,name='Changed fee'");
                } else {
                    jdbc.execute("UPDATE invoices SET grand_total=999.99");
                    jdbc.execute("UPDATE invoice_line_snapshots SET total=999.99,metadata='{\"changed\":true}'::jsonb");
                }
            } finally {
                changesCommitted.countDown();
            }
            assertComplete(request.get(20, TimeUnit.SECONDS), endpoint);
        } finally {
            capture.beforeCollectionFetch = null;
        }
    }

    private JsonNode page(String endpoint, String query) throws Exception {
        var response = get("/" + endpoint + query);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json(response);
    }

    private void assertBoundedSql(String endpoint) {
        assertThat(capture.statements.getFirst()).contains("select id from " + endpoint,
                "order by id asc", "limit ?", "offset ?").doesNotContain("join");
        capture.statements.stream().skip(1).forEach(sql -> {
            assertThat(sql).contains(" in (").doesNotContain("limit", "offset", "fetch first");
        });
    }

    private void assertComplete(JsonNode page, String endpoint) {
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("currency").asString()).isEqualTo("EUR");
            assertThat(item.get("created_at").isString()).isTrue();
            if (endpoint.equals("transactions")) {
                assertThat(item.get("total").decimalValue()).isEqualByComparingTo("123.45");
                assertNested(item);
            } else {
                assertThat(item.get("grand_total").decimalValue()).isEqualByComparingTo("246.90");
                assertThat(item.get("lines").size()).isEqualTo(2);
                for (JsonNode line : item.get("lines")) {
                    assertThat(line.get("transaction_id").longValue()).isPositive();
                    assertThat(line.get("total").decimalValue()).isEqualByComparingTo("123.45");
                    assertNested(line);
                }
                assertJsonEquivalent(item.get("tax_rollup"), mapper.readTree("""
                        [{"name":"Tax A","amount":2.22},{"name":"Tax B","rate":0.07,"amount":4.44}]
                        """));
                assertJsonEquivalent(item.get("fee_rollup"), mapper.readTree("""
                        [{"name":"Fee A","amount":0.20},{"name":"Fee B","amount":0.40},{"name":"Fee C","amount":0.60}]
                        """));
            }
        }
    }

    private void assertNested(JsonNode item) {
        assertThat(item.get("type").asString()).isEqualTo("flight");
        assertJsonEquivalent(item.get("metadata"), payload("flight").get("metadata"));
        assertThat(item.get("tax_lines").size()).isEqualTo(2);
        assertThat(item.get("fee_lines").size()).isEqualTo(3);
        assertThat(item.get("tax_lines").valueStream().map(n -> n.get("name").asString()).toList())
                .containsExactlyInAnyOrder("Tax A", "Tax B");
        assertThat(item.get("fee_lines").valueStream().map(n -> n.get("name").asString()).toList())
                .containsExactlyInAnyOrder("Fee A", "Fee B", "Fee C");
        assertThat(item.get("tax_lines").valueStream().map(n -> n.get("amount").decimalValue()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("3.33");
        assertThat(item.get("fee_lines").valueStream().map(n -> n.get("amount").decimalValue()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo("0.60");
    }

    private List<Long> ids(JsonNode page) {
        return page.get("items").valueStream().map(item -> item.get("id").longValue()).toList();
    }

    private List<Long> expectedIds(String endpoint, int offset, int size) {
        long multiplier = endpoint.equals("transactions") ? 3 : 5;
        return LongStream.range(offset + 1L, Math.min(125L, offset + (long) size) + 1).map(n -> n * multiplier).boxed().toList();
    }

    private Statistics statistics() { return entityManagerFactory.unwrap(SessionFactory.class).getStatistics(); }
    private void clearMeasurements() { statistics().clear(); capture.statements.clear(); }
    private void assertLoads(Class<?> type, long count) {
        assertThat(statistics().getEntityStatistics(type.getName()).getLoadCount()).isEqualTo(count);
    }
}
