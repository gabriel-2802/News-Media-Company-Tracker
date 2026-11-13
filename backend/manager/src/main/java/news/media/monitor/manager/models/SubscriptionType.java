package news.media.monitor.manager.models;

public enum SubscriptionType {
    TOPIC,
    STORY,
    /** A listed company's symbol (watchlist entry); alerts come from event.upserted, not from the provider. */
    TICKER
}
