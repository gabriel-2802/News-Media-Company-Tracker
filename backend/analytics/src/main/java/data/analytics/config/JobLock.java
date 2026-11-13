package data.analytics.config;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.UUID;

/**
 * Lease-based lock in the job_locks table, so only one analytics replica runs
 * a scheduled job at a time. A crashed holder's lease expires. (ShedLock does
 * the same; this avoids a dependency not yet confirmed for Spring Framework 7.)
 */
@Component
@RequiredArgsConstructor
public class JobLock {

    private static final String OWNER = ManagementFactory.getRuntimeMXBean().getName() + "-" + UUID.randomUUID();

    private final JdbcClient jdbc;

    public boolean tryAcquire(final String name, final Duration lease) {
        return jdbc.sql("""
                INSERT INTO job_locks (name, owner, locked_until) VALUES (?, ?, now() + make_interval(secs => ?))
                ON CONFLICT (name) DO UPDATE SET owner = EXCLUDED.owner, locked_until = EXCLUDED.locked_until
                WHERE job_locks.locked_until < now() OR job_locks.owner = EXCLUDED.owner
                """).params(name, OWNER, (double) lease.toSeconds()).update() == 1;
    }

    public void release(final String name) {
        jdbc.sql("UPDATE job_locks SET locked_until = now() WHERE name = ? AND owner = ?").params(name, OWNER).update();
    }
}
