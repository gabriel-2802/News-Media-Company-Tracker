package data.provider.config;

import org.springframework.dao.TransientDataAccessException;

/**
 * Detects Neo4j transient errors (deadlocks, lock timeouts).
 *
 * Inside a transaction a deadlock often surfaces as "Cannot run more queries
 * in this transaction" (InvalidDataAccessResourceUsageException) with the
 * driver's TransientException further down the cause chain, so the whole
 * chain is inspected rather than the top-level exception type.
 */
public final class TransientNeo4jError {

    private TransientNeo4jError() {
    }

    public static boolean isTransient(final Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof org.neo4j.driver.exceptions.TransientException) {
                return true;
            }
        }
        return false;
    }
}
