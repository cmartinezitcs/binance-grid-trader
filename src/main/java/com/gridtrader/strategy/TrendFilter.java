package com.gridtrader.strategy;

import com.gridtrader.api.BinanceRestClient;
import com.gridtrader.config.AppConfig;
import com.gridtrader.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TrendFilter {

    private static final Logger log = LoggerFactory.getLogger(TrendFilter.class);

    public enum Trend {
        BULLISH, NEUTRAL, BEARISH;

        public String label() {
            return switch (this) {
                case BULLISH -> "ALCISTA";
                case NEUTRAL -> "LATERAL";
                case BEARISH -> "BAJISTA";
            };
        }
    }

    private final AppConfig         config;
    private final BinanceRestClient client;

    private volatile Trend  trend       = Trend.NEUTRAL;
    private volatile double fastMa      = 0;
    private volatile double slowMa      = 0;
    private volatile long   lastRefresh = 0;

    public TrendFilter(AppConfig config, BinanceRestClient client) {
        this.config = config;
        this.client = client;
    }

    /**
     * Recalcula la tendencia si ha pasado el intervalo de refresco configurado.
     * Usa una sola llamada a la API para obtener las velas de ambas MAs.
     *
     * Lógica:
     *   precio > MA_rápida > MA_lenta → ALCISTA
     *   precio < MA_rápida < MA_lenta → BAJISTA
     *   cualquier otra combinación   → LATERAL
     */
    public Trend refresh(double currentPrice) {
        if (!config.isTrendFilterEnabled()) {
            return Trend.NEUTRAL;
        }

        long elapsedMs = System.currentTimeMillis() - lastRefresh;
        long intervalMs = config.getTrendCheckInterval() * 1000L;

        if (lastRefresh > 0 && elapsedMs < intervalMs) {
            return trend; // devolver valor cacheado
        }

        try {
            int slowPeriod = config.getTrendSlowPeriod();
            int fastPeriod = config.getTrendFastPeriod();

            // Una sola petición: pedimos slowPeriod+1 velas (suficiente para ambas MAs)
            List<Kline> klines = client.getKlines(
                config.getSymbol(),
                config.getTrendInterval(),
                slowPeriod + 1
            );

            if (klines.size() < slowPeriod) {
                log.warn("[TrendFilter] Datos insuficientes ({} velas, necesarias {}). Tendencia: LATERAL",
                    klines.size(), slowPeriod);
                return trend;
            }

            double prevFast = this.fastMa;
            double prevSlow = this.slowMa;

            this.fastMa = sma(klines, fastPeriod);
            this.slowMa = sma(klines, slowPeriod);

            Trend prevTrend = this.trend;

            if (currentPrice > fastMa && fastMa > slowMa) {
                this.trend = Trend.BULLISH;
            } else if (currentPrice < fastMa && fastMa < slowMa) {
                this.trend = Trend.BEARISH;
            } else {
                this.trend = Trend.NEUTRAL;
            }

            this.lastRefresh = System.currentTimeMillis();

            if (prevTrend != this.trend) {
                log.info("[TrendFilter] Cambio de tendencia: {} → {} | MA{}: {:.2f} | MA{}: {:.2f} | Precio: {:.2f}",
                    prevTrend.label(), trend.label(),
                    fastPeriod, fastMa,
                    slowPeriod, slowMa,
                    currentPrice);
            } else {
                log.debug("[TrendFilter] {} | MA{}: {:.2f} (ant: {:.2f}) | MA{}: {:.2f} (ant: {:.2f})",
                    trend.label(), fastPeriod, fastMa, prevFast, slowPeriod, slowMa, prevSlow);
            }

        } catch (Exception e) {
            log.error("[TrendFilter] Error calculando MAs: {}. Usando tendencia anterior: {}",
                e.getMessage(), trend.label());
        }

        return trend;
    }

    /**
     * Devuelve true si el filtro de tendencia permite abrir nuevas compras.
     * En tendencia BAJISTA se suspenden las nuevas órdenes de compra para
     * evitar comprar en una caída libre sin fondo visible.
     */
    public boolean allowBuy() {
        if (!config.isTrendFilterEnabled()) return true;
        return trend != Trend.BEARISH;
    }

    /**
     * Calcula la SMA de los últimos `period` cierres de la lista de velas.
     */
    private double sma(List<Kline> klines, int period) {
        int size = klines.size();
        int from = Math.max(0, size - period);
        double sum = 0;
        int count = 0;
        for (int i = from; i < size; i++) {
            sum += klines.get(i).getClose();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public Trend  getTrend()        { return trend; }
    public double getFastMa()       { return fastMa; }
    public double getSlowMa()       { return slowMa; }
    public int    getFastPeriod()   { return config.getTrendFastPeriod(); }
    public int    getSlowPeriod()   { return config.getTrendSlowPeriod(); }
    public long   getLastRefresh()  { return lastRefresh; }
    public boolean isEnabled()      { return config.isTrendFilterEnabled(); }
}
