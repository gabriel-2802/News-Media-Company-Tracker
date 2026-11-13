package data.analytics.events;

import data.analytics.provider.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rebuilds the read model from GET /api/events, e.g. after the market
 * database was wiped or messages were missed. Version guards make it safe to
 * run while event.upserted messages keep arriving.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventRebuildService {

    private static final int PAGE = 500;

    private final ProviderClient provider;
    private final EventRepository events;

    public int rebuild(final String since) {
        int applied = 0;
        int page = 0;
        List<EventMessage> batch;
        do {
            batch = provider.events(since, page++, PAGE);
            for (EventMessage m : batch) {
                if (events.apply(m)) {
                    applied++;
                }
            }
        } while (batch.size() == PAGE);
        log.info("Event rebuild since {}: {} change(s) applied, {} events stored", since, applied, events.count());
        return applied;
    }
}
