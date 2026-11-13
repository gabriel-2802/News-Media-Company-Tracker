package data.analytics.market;

import data.analytics.provider.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Local copy of the provider's listings (symbol, exchange -> company), refreshed by the price ingest. */
@Component
@Slf4j
@RequiredArgsConstructor
public class CompanyDirectory {

    private final JdbcClient jdbc;
    private final ProviderClient provider;

    /** Best effort at startup so charts have names before the first ingest; logs if the provider is down. */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        try {
            refresh(provider.companies());
        } catch (RuntimeException e) {
            log.warn("Company directory not refreshed at startup (provider unavailable?): {}", e.getMessage());
        }
    }

    public int refreshFromProvider() {
        return refresh(provider.companies());
    }

    /**
     * Replaces the directory with the given listings. An empty or missing list is never applied: the
     * provider always seeds a company universe, so "no companies" means a failed or mocked call, and
     * wiping the table would strip names from every chart until the next successful refresh.
     */
    @Transactional
    public int refresh(final List<ProviderClient.CompanyListing> companies) {
        if (companies == null || companies.isEmpty()) {
            log.warn("Provider returned no companies; keeping the existing company directory");
            return 0;
        }
        jdbc.sql("DELETE FROM companies").update();
        int n = 0;
        for (ProviderClient.CompanyListing c : companies) {
            for (ProviderClient.CompanyListing.Ticker t : c.tickers()) {
                n += jdbc.sql("""
                        INSERT INTO companies (symbol, exchange, company_id, name, kind, currency, is_primary)
                        VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (symbol, exchange) DO NOTHING
                        """).params(t.symbol(), t.exchange(), c.id(), c.name(), c.kind(), t.currency(), t.primary()).update();
            }
        }
        log.info("Company directory refreshed: {} listings", n);
        return n;
    }
}
