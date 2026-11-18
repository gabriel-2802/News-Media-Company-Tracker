package news.media.monitor.manager.models;

public enum SubscriptionType {
    /** A banking topic; article notifications come from the provider (article.notifications). */
    TOPIC,
    /** A listed company's symbol (watchlist entry); alerts come from event.upserted, not from the provider. */
    TICKER
}
