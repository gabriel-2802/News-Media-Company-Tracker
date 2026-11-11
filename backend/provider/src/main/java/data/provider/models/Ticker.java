package data.provider.models;

import data.provider.util.UuidStringIdGenerator;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/** A listed security. Unique on (symbol, exchange). */
@Node("Ticker")
@Data
@Builder
public class Ticker {
    @Id
    @GeneratedValue(UuidStringIdGenerator.class)
    private String id;

    /** Exchange symbol as used by the market-data provider, e.g. "JPM", "BARC.L". */
    private String symbol;
    /** MIC or short exchange name, e.g. "XNYS", "XLON". */
    private String exchange;
    /** ISO 4217 trading currency, e.g. "USD", "GBP". */
    private String currency;
}
