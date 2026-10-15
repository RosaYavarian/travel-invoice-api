package com.navan.travelinvoice;

import com.navan.travelinvoice.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TravelInvoiceApiApplicationTests extends PostgresIntegrationTestSupport {
    @Test
    void contextLoadsAndFlywayMigrationsAreApplied() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1', '2')",
                Integer.class)).isEqualTo(2);
    }

}
