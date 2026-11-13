package news.media.monitor.manager.exceptions.exceptions;

public class WatchlistLimitException extends RuntimeException {
    public WatchlistLimitException(int max) {
        super("A watchlist can hold at most " + max + " tickers");
    }
}
