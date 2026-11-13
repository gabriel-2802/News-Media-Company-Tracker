package data.analytics.study;

import data.analytics.market.Bar;
import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.regression.SimpleRegression;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Market-model event study (MacKinlay 1997).
 *
 * Daily log returns on days where both the stock and the benchmark traded.
 * Day 0 is the first such day on or after the event date. Over the
 * estimation window [estimationStart, estimationEnd] (trading days relative
 * to day 0, e.g. [-250, -30]) fit r_stock = alpha + beta * r_bench by OLS.
 * Abnormal return AR_t = r_t - (alpha + beta * r_bench,t).
 * CAR[-1,+1] and CAR[-5,+5] sum the ARs; a window is reported only once all
 * its days exist. Significance of CAR[-1,+1]: t = CAR / (sigma * sqrt(3))
 * with sigma the residual standard error of the fit, two-sided Student t
 * with n - 2 degrees of freedom.
 */
public final class EventStudy {

    public record Result(LocalDate eventDay, double alpha, double beta, double ar0, Double car1, Double car5,
                         Double pValue, int estimationDays, boolean complete) {}

    private final int estimationStart;
    private final int estimationEnd;
    private final int minEstimationDays;

    public EventStudy(final int estimationStart, final int estimationEnd, final int minEstimationDays) {
        if (estimationStart >= estimationEnd || estimationEnd >= -1) {
            throw new IllegalArgumentException("estimation window must end before day -1");
        }
        this.estimationStart = estimationStart;
        this.estimationEnd = estimationEnd;
        this.minEstimationDays = minEstimationDays;
    }

    /** Empty if day 0 has not traded yet or the estimation window is too short. */
    public Optional<Result> run(final List<Bar> stock, final List<Bar> benchmark, final LocalDate eventDate) {
        final TreeMap<LocalDate, Double> s = closes(stock);
        final TreeMap<LocalDate, Double> m = closes(benchmark);
        final List<LocalDate> days = new ArrayList<>(s.keySet());
        days.retainAll(m.keySet());

        // returns[i] is the log return from days[i-1] to days[i]
        final List<LocalDate> retDays = new ArrayList<>();
        final List<double[]> rets = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            final LocalDate d = days.get(i);
            final LocalDate p = days.get(i - 1);
            retDays.add(d);
            rets.add(new double[]{Math.log(s.get(d) / s.get(p)), Math.log(m.get(d) / m.get(p))});
        }

        int t0 = -1;
        for (int i = 0; i < retDays.size(); i++) {
            if (!retDays.get(i).isBefore(eventDate)) {
                t0 = i;
                break;
            }
        }
        if (t0 < 0) {
            return Optional.empty();
        }

        final SimpleRegression ols = new SimpleRegression(true);
        for (int i = Math.max(0, t0 + estimationStart); i <= t0 + estimationEnd; i++) {
            ols.addData(rets.get(i)[1], rets.get(i)[0]);
        }
        final int n = (int) ols.getN();
        if (n < minEstimationDays) {
            return Optional.empty();
        }
        final double alpha = ols.getIntercept();
        final double beta = ols.getSlope();
        final double sigma = Math.sqrt(ols.getMeanSquareError());

        final int last = rets.size() - 1;
        final double ar0 = ar(rets, t0, alpha, beta);
        final Double car1 = t0 - 1 >= 0 && t0 + 1 <= last ? sum(rets, t0 - 1, t0 + 1, alpha, beta) : null;
        final Double car5 = t0 - 5 >= 0 && t0 + 5 <= last ? sum(rets, t0 - 5, t0 + 5, alpha, beta) : null;
        Double p = null;
        if (car1 != null && sigma > 0) {
            final double t = car1 / (sigma * Math.sqrt(3));
            p = 2 * (1 - new TDistribution(n - 2).cumulativeProbability(Math.abs(t)));
        }
        return Optional.of(new Result(retDays.get(t0), alpha, beta, ar0, car1, car5, p, n, car5 != null));
    }

    private static double ar(final List<double[]> rets, final int i, final double alpha, final double beta) {
        return rets.get(i)[0] - (alpha + beta * rets.get(i)[1]);
    }

    private static double sum(final List<double[]> rets, final int from, final int to, final double alpha, final double beta) {
        double total = 0;
        for (int i = from; i <= to; i++) {
            total += ar(rets, i, alpha, beta);
        }
        return total;
    }

    private static TreeMap<LocalDate, Double> closes(final List<Bar> bars) {
        final TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (Bar b : bars) {
            if (b.close() > 0) {
                map.put(b.day(), b.close());
            }
        }
        return map;
    }

    static Map.Entry<String, String> parseListing(final String symbolAtMic) {
        final int at = symbolAtMic.lastIndexOf('@');
        return Map.entry(symbolAtMic.substring(0, at), symbolAtMic.substring(at + 1));
    }
}
