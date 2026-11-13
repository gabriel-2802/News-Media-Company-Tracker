package data.analytics.market;

import data.analytics.provider.ProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * An empty company list (provider failure, or a mocked provider in tests) must never wipe the directory.
 * Only the empty paths are exercised: a real refresh replaces the whole table, which is shared with the
 * running system.
 */
@SpringBootTest
class CompanyDirectoryIntegrationTests {

    @MockitoBean private ProviderClient provider;
    @Autowired private CompanyDirectory directory;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void insertMarker() {
        jdbc.sql("INSERT INTO companies (symbol, exchange, company_id, name, kind, currency, is_primary) "
                + "VALUES ('ITDIR', 'XTST', 'it-dir', 'Directory Test Bank', 'BANK', 'USD', true) "
                + "ON CONFLICT (symbol, exchange) DO NOTHING").update();
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM companies WHERE exchange = 'XTST' AND symbol = 'ITDIR'").update();
    }

    private long markerRows() {
        return jdbc.sql("SELECT count(*) FROM companies WHERE symbol = 'ITDIR' AND exchange = 'XTST'")
                .query(Long.class).single();
    }

    @Test
    void emptyOrMissingListKeepsTheDirectory() {
        assertThat(directory.refresh(List.of())).isZero();
        assertThat(directory.refresh(null)).isZero();
        assertThat(markerRows()).isEqualTo(1);
    }

    @Test
    void providerReturningNothingKeepsTheDirectory() {
        when(provider.companies()).thenReturn(List.of());
        assertThat(directory.refreshFromProvider()).isZero();
        directory.refreshOnStartup();
        assertThat(markerRows()).isEqualTo(1);
    }
}
