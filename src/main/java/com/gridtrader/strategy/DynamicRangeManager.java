package com.gridtrader.strategy;

import com.gridtrader.api.BinanceRestClient;
import com.gridtrader.config.AppConfig;
import com.gridtrader.model.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DynamicRangeManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicRangeManager.class);

    private final AppConfig config;
    private final BinanceRestClient client;

    private double lowerBound;
    private double upperBound;
    private double lastAtr;
    private long   lastCalculationTime;

    public DynamicRangeManager(AppConfig config, BinanceRestClient client) {
        this.config = config;
        this.client = client;
    }

    /**
     * Calcula los límites del rango de la cuadrícula.
     * Con dynamic=true usa ATR; de lo contrario usa spacing.percent.
     */
    public double[] calculateRange(double currentPrice) {
        if (config.isDynamicRangeEnabled()) {
            calculateAtrRange(currentPrice);
        } else {
            calculateFixedRange(currentPrice);
        }
        lastCalculationTime = System.currentTimeMillis();

        log.info("Rango calculado: [{:.2f} - {:.2f}] | ATR={:.4f} | Amplitud={:.2f}%",
            lowerBound, upperBound, lastAtr,
            (upperBound - lowerBound) / currentPrice * 100);

        return new double[]{ lowerBound, upperBound };
    }

    /**
     * Determina si el precio actual requiere un rebalanceo de la cuadrícula.
     * Se activa cuando el precio supera el umbral configurado hacia alguno de los bordes.
     */
    public boolean needsRebalance(double currentPrice) {
        double range = upperBound - lowerBound;
        double threshold = config.getRebalanceThreshold();

        boolean nearUpper = currentPrice >= lowerBound + range * threshold;
        boolean nearLower = currentPrice <= lowerBound + range * (1.0 - threshold);

        if (nearUpper) {
            log.info("Precio {:.4f} cerca del borde superior {:.4f} — rebalanceo necesario", currentPrice, upperBound);
        } else if (nearLower) {
            log.info("Precio {:.4f} cerca del borde inferior {:.4f} — rebalanceo necesario", currentPrice, lowerBound);
        }

        return nearUpper || nearLower;
    }

    // -------------------------------------------------------
    //  CÁLCULO ATR
    // -------------------------------------------------------

    private void calculateAtrRange(double currentPrice) {
        double atr = computeAtr();
        this.lastAtr = atr;

        double range = atr * config.getAtrMultiplier();
        this.lowerBound = Math.max(0, currentPrice - range);
        this.upperBound = currentPrice + range;

        log.debug("ATR={:.4f} x{} = rango ±{:.4f}", atr, config.getAtrMultiplier(), range);
    }

    private double computeAtr() {
        int period = config.getAtrPeriod();
        // +1 vela para calcular el True Range de la primera vela del período
        List<Kline> klines = client.getKlines(config.getSymbol(), config.getAtrInterval(), period + 1);

        if (klines.size() < 2) {
            throw new RuntimeException("No hay suficientes klines para calcular ATR (mínimo 2)");
        }

        double[] trValues = new double[klines.size() - 1];
        for (int i = 1; i < klines.size(); i++) {
            trValues[i - 1] = klines.get(i).trueRange(klines.get(i - 1).getClose());
        }

        // Wilder's Smoothed Moving Average (SMA para la primera iteración)
        double atr = 0;
        for (double tr : trValues) atr += tr;
        atr /= trValues.length;

        return atr;
    }

    // -------------------------------------------------------
    //  RANGO FIJO
    // -------------------------------------------------------

    private void calculateFixedRange(double currentPrice) {
        this.lastAtr = 0;
        double halfRange;

        if ("ARITHMETIC".equals(config.getSpacingType())) {
            // Rango total = spacing_percent * levels velas
            halfRange = currentPrice * (config.getSpacingPercent() / 100.0)
                        * config.getGridLevels() / 2.0;
        } else {
            // GEOMETRIC: cada nivel es spacing_percent% más alto
            // upperBound = currentPrice * (1 + pct)^(levels/2)
            double factor = Math.pow(1 + config.getSpacingPercent() / 100.0, config.getGridLevels() / 2.0);
            this.upperBound = currentPrice * factor;
            this.lowerBound = currentPrice / factor;
            return;
        }
        this.lowerBound = Math.max(0, currentPrice - halfRange);
        this.upperBound = currentPrice + halfRange;
    }

    // -------------------------------------------------------
    //  GETTERS
    // -------------------------------------------------------

    public double getLowerBound()           { return lowerBound; }
    public double getUpperBound()           { return upperBound; }
    public double getLastAtr()              { return lastAtr; }
    public long   getLastCalculationTime()  { return lastCalculationTime; }
}
