package data.analytics.market;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PriceRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public int upsert(final String symbol, final String exchange, final String source, final List<Bar> bars) {
        final int[][] counts = template.batchUpdate("""
                INSERT INTO prices_daily (symbol, exchange, day, open, high, low, close, volume, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (symbol, exchange, day) DO UPDATE SET
                    open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
                    close = EXCLUDED.close, volume = EXCLUDED.volume, source = EXCLUDED.source
                """, bars, 500, (ps, b) -> {
            ps.setString(1, symbol);
            ps.setString(2, exchange);
            ps.setDate(3, Date.valueOf(b.day()));
            ps.setObject(4, b.open(), Types.DOUBLE);
            ps.setObject(5, b.high(), Types.DOUBLE);
            ps.setObject(6, b.low(), Types.DOUBLE);
            ps.setDouble(7, b.close());
            ps.setObject(8, b.volume(), Types.BIGINT);
            ps.setString(9, source);
        });
        int total = 0;
        for (int[] batch : counts) {
            total += batch.length;
        }
        return total;
    }

    public List<Bar> range(final String symbol, final String exchange, final LocalDate from, final LocalDate to) {
        return jdbc.sql("""
                SELECT day, open, high, low, close, volume FROM prices_daily
                WHERE symbol = ? AND exchange = ? AND day BETWEEN ? AND ? ORDER BY day
                """).params(symbol, exchange, from, to)
                .query((rs, i) -> new Bar(rs.getObject(1, LocalDate.class), (Double) rs.getObject(2), (Double) rs.getObject(3),
                        (Double) rs.getObject(4), rs.getDouble(5), (Long) rs.getObject(6)))
                .list();
    }

    public Optional<LocalDate> lastDay(final String symbol, final String exchange) {
        return jdbc.sql("SELECT max(day) FROM prices_daily WHERE symbol = ? AND exchange = ?")
                .params(symbol, exchange).query(LocalDate.class).optional();
    }

    public void recordRun(final String symbol, final String exchange, final LocalDate lastDay, final String error) {
        jdbc.sql("""
                INSERT INTO ingest_state (symbol, exchange, last_day, last_run, last_error) VALUES (?, ?, ?, now(), ?)
                ON CONFLICT (symbol, exchange) DO UPDATE SET
                    last_day = COALESCE(EXCLUDED.last_day, ingest_state.last_day), last_run = now(), last_error = EXCLUDED.last_error
                """).params(symbol, exchange, lastDay, error).update();
    }
}
