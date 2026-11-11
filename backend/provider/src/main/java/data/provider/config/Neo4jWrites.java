package data.provider.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs a multi-statement Neo4j write as one transaction, retried in a fresh
 * transaction on transient errors (deadlocks).
 *
 * Programmatic on purpose: with annotations, @Retryable ended up inside the
 * @Transactional interceptor, so retries reused the already-failed
 * transaction. Here the order (retry outside, transaction inside) is explicit.
 * Lock ordering (see EventService) prevents most deadlocks; this is the
 * fallback.
 */
@Component
@Slf4j
public class Neo4jWrites {

    private final TransactionTemplate tx;
    private final RetryTemplate retry;

    public Neo4jWrites(final PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(6)
                .delay(Duration.ofMillis(50))
                .jitter(Duration.ofMillis(50))
                .multiplier(2)
                .maxDelay(Duration.ofSeconds(1))
                .predicate(TransientNeo4jError::isTransient)
                .build());
    }

    public <T> T write(final String name, final Supplier<T> work) {
        try {
            return retry.execute(new Retryable<T>() {
                @Override
                public T execute() {
                    try {
                        return tx.execute(status -> work.get());
                    } catch (RuntimeException e) {
                        if (TransientNeo4jError.isTransient(e)) {
                            log.info("Retrying {} after transient Neo4j error (likely a deadlock)", name);
                        }
                        throw e;
                    }
                }

                @Override
                public String getName() {
                    return name;
                }
            });
        } catch (RetryException e) {
            // Unwrap so business errors (400) and exhausted transient errors (503) keep their type.
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }
}
