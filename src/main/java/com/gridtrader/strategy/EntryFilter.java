package com.gridtrader.strategy;

import com.gridtrader.api.BinanceRestClient;
import com.gridtrader.config.AppConfig;
import com.gridtrader.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * RSI + Bollinger Bands entry filter.
 *
 * Signal logic:
 *   FAVORABLE  — RSI < oversold  OR  %B < bbThreshold  (or both, configurable)
 *   UNFAVORABLE— RSI > overbought AND %B > 0.85
 *   NEUTRAL    — everything else
 *
 * When requireBoth=true: FAVORABLE only when RSI < oversold AND %B < bbThreshold.
 */
public class EntryFilter {

    private static final Logger log = LoggerFactory.getLogger(EntryFilter.class);

    public enum Signal { FAVORABLE, NEUTRAL, UNFAVORABLE }

    private final boolean           enabled;
    private final BinanceRestClient client;
    private final String            symbol;
    private final String            interval;
    private final int               rsiPeriod;
    private final double            rsiOversold;
    private final double            rsiOverbought;
    private final int               bbPeriod;
    private final double            bbStdDev;
    private final double            bbThreshold;
    private final boolean           requireBoth;
    private final long              cacheSeconds;

    private Signal  lastSignal        = Signal.NEUTRAL;
    private double  lastRsi           = 50.0;
    private double  lastBbPercent     = 0.5;
    private double  lastBbUpper       = 0;
    private double  lastBbLower       = 0;
    private long    lastEvalTime      = 0;

    public EntryFilter(AppConfig config, BinanceRestClient client) {
        this.enabled      = config.isEntryFilterEnabled();
        this.client       = client;
        this.symbol       = config.getSymbol();
        this.interval     = config.getEntryFilterInterval();
        this.rsiPeriod    = config.getEntryFilterRsiPeriod();
        this.rsiOversold  = config.getEntryFilterRsiOversold();
        this.rsiOverbought= config.getEntryFilterRsiOverbought();
        this.bbPeriod     = config.getEntryFilterBbPeriod();
        this.bbStdDev     = config.getEntryFilterBbStdDev();
        this.bbThreshold  = config.getEntryFilterBbThreshold();
        this.requireBoth  = config.isEntryFilterRequireBoth();
        this.cacheSeconds = config.getEntryFilterCheckInterval();
    }

    public boolean isEnabled() { return enabled; }

    /** Returns true when the current signal is FAVORABLE (good time to buy). */
    public boolean isGoodEntry() {
        if (!enabled) return true;
        return lastSignal == Signal.FAVORABLE;
    }

    /** Evaluates RSI and BB%B using the latest klines. Caches result for cacheSeconds. */
    public Signal evaluate(double currentPrice) {
        if (!enabled) return Signal.NEUTRAL;

        long now = System.currentTimeMillis() / 1000;
        if (now - lastEvalTime < cacheSeconds && lastEvalTime > 0) {
            return lastSignal;
        }

        try {
            // rsiPeriod bars for seed + rsiPeriod bars for Wilder warm-up + bbPeriod + margin
            int limit = rsiPeriod * 2 + bbPeriod + 10;
            List<Kline> klines = client.getKlines(symbol, interval, limit);

            double rsi = computeRsi(klines);
            double[] bb = computeBollingerBands(klines);
            double upper = bb[0];
            double lower = bb[1];
            double bbPct = (upper == lower) ? 0.5 : (currentPrice - lower) / (upper - lower);

            Signal prev = lastSignal;
            lastRsi       = rsi;
            lastBbPercent = bbPct;
            lastBbUpper   = upper;
            lastBbLower   = lower;
            lastEvalTime  = now;

            boolean oversoldRsi = rsi < rsiOversold;
            boolean lowBb       = bbPct < bbThreshold;
            boolean overboughtRsi = rsi > rsiOverbought;
            boolean highBb      = bbPct > 0.85;

            if (requireBoth) {
                lastSignal = (oversoldRsi && lowBb) ? Signal.FAVORABLE
                           : (overboughtRsi && highBb) ? Signal.UNFAVORABLE
                           : Signal.NEUTRAL;
            } else {
                lastSignal = (oversoldRsi || lowBb) ? Signal.FAVORABLE
                           : (overboughtRsi && highBb) ? Signal.UNFAVORABLE
                           : Signal.NEUTRAL;
            }

            if (prev != lastSignal) {
                log.info("[EntryFilter] Señal: {} → {} | RSI: {:.1f} | BB%B: {:.3f}",
                    prev, lastSignal, rsi, bbPct);
            }

        } catch (Exception e) {
            log.warn("[EntryFilter] Error evaluando RSI/BB: {} — manteniendo señal anterior", e.getMessage());
        }

        return lastSignal;
    }

    // ── RSI con suavizado de Wilder ───────────────────────────────────────────

    private double computeRsi(List<Kline> klines) {
        int n = klines.size();
        if (n < rsiPeriod * 2 + 1) return 50.0;

        // Calcular todos los cambios de precio
        double[] changes = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            changes[i] = klines.get(i + 1).getClose() - klines.get(i).getClose();
        }

        // Seed: promedio simple de los primeros rsiPeriod cambios
        double avgGain = 0, avgLoss = 0;
        for (int i = 0; i < rsiPeriod; i++) {
            if (changes[i] > 0) avgGain += changes[i];
            else                avgLoss -= changes[i];
        }
        avgGain /= rsiPeriod;
        avgLoss /= rsiPeriod;

        // Wilder's smoothing: EMA(1/period) para el resto de las barras
        for (int i = rsiPeriod; i < changes.length; i++) {
            double gain = changes[i] > 0 ? changes[i] : 0.0;
            double loss = changes[i] < 0 ? -changes[i] : 0.0;
            avgGain = (avgGain * (rsiPeriod - 1) + gain) / rsiPeriod;
            avgLoss = (avgLoss * (rsiPeriod - 1) + loss) / rsiPeriod;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ── Bollinger Bands ───────────────────────────────────────────────────────

    private double[] computeBollingerBands(List<Kline> klines) {
        int n = klines.size();
        if (n < bbPeriod) return new double[]{0, 0};

        double sum = 0;
        int start = n - bbPeriod;
        for (int i = start; i < n; i++) sum += klines.get(i).getClose();
        double sma = sum / bbPeriod;

        double variance = 0;
        for (int i = start; i < n; i++) {
            double diff = klines.get(i).getClose() - sma;
            variance += diff * diff;
        }
        double std = Math.sqrt(variance / bbPeriod);

        double upper = sma + bbStdDev * std;
        double lower = sma - bbStdDev * std;
        return new double[]{upper, lower};
    }

    // ── Getters para el monitor ───────────────────────────────────────────────

    public Signal  getSignal()      { return lastSignal; }
    public double  getRsi()         { return lastRsi; }
    public double  getBbPercent()   { return lastBbPercent; }
    public double  getBbUpper()     { return lastBbUpper; }
    public double  getBbLower()     { return lastBbLower; }
    public int     getRsiPeriod()   { return rsiPeriod; }
    public double  getRsiOversold() { return rsiOversold; }
    public double  getRsiOverbought(){ return rsiOverbought; }
}
