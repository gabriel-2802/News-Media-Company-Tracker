package data.analytics.market;

import java.time.LocalDate;

/** One daily bar of a listing. */
public record Bar(LocalDate day, Double open, Double high, Double low, double close, Long volume) {}
