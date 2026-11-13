package data.analytics.events;

import data.analytics.provider.ProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds the read model from GET /api/events, e.g. after the market
 * database was wiped or messages were missed. Version guards make it safe to
 * run while event.upserted messages keep arriving.
 *
 * A full rebuild (no {@code since}) also reconciles: the provider's feed only
 * lists events that exist, so an event removed upstream without a delivered
 * tombstone (a lost message, a manual graph repair) would otherwise stay here
 * for ever. Stored events missing from the full feed are removed through the
 * normal tombstone path, but only if their version predates the rebuild
 * (newer rows came from live messages during it). An empty feed removes
 * nothing: the provider always has events once articles exist, so empty means
 * a failed or mocked call.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventRebuildService {

    private static final int PAGE = 500;

    private final ProviderClient provider;
    private final EventRepository events;

    public int rebuild(final String since) {
        final long startedAt = System.currentTimeMillis();
        final Set<List<String>> seen = new HashSet<>();
        int applied = 0;
        int page = 0;
        List<EventMessage> batch;
        do {
            batch = provider.events(since, page++, PAGE);
            for (EventMessage m : batch) {
                seen.add(List.of(m.storyId(), m.companyId()));
                if (events.apply(m)) {
                    applied++;
                }
            }
        } while (batch.size() == PAGE);

        int removed = 0;
        if (since == null && !seen.isEmpty()) {
            for (List<String> key : events.keysWithVersionBefore(startedAt)) {
                if (!seen.contains(key) && events.apply(EventMessage.tombstone(key.get(0), key.get(1), startedAt))) {
                    removed++;
                }
            }
        } else if (since == null) {
            log.warn("Provider returned no events; full rebuild removes nothing");
        }
        log.info("Event rebuild since {}: {} change(s) applied, {} removed as missing upstream, {} events stored",
                since, applied, removed, events.count());
        return applied + removed;
    }
}
