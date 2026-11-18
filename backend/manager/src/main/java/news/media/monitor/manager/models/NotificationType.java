package news.media.monitor.manager.models;

public enum NotificationType {
    /** New article for a subscribed topic. */
    ARTICLE,
    /** Event on a watched ticker that passed the user's impact threshold and topic filter. */
    TICKER_ALERT
}
