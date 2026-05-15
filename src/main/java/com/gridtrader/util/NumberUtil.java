package com.gridtrader.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberUtil {

    private NumberUtil() {}

    /**
     * Redondea un precio al tick size del exchange.
     * tickSize es la cadena del filtro, ej: "0.01", "0.10", "1.00"
     */
    public static double roundToTickSize(double price, String tickSize) {
        BigDecimal bd = new BigDecimal(String.valueOf(price));
        BigDecimal tick = new BigDecimal(tickSize);
        return bd.divide(tick, 0, RoundingMode.HALF_DOWN)
                 .multiply(tick)
                 .stripTrailingZeros()
                 .doubleValue();
    }

    /**
     * Redondea una cantidad al step size del exchange.
     * stepSize es la cadena del filtro, ej: "0.001", "0.00001"
     */
    public static double roundToStepSize(double qty, String stepSize) {
        BigDecimal bd = new BigDecimal(String.valueOf(qty));
        BigDecimal step = new BigDecimal(stepSize);
        return bd.divide(step, 0, RoundingMode.DOWN)
                 .multiply(step)
                 .stripTrailingZeros()
                 .doubleValue();
    }

    /**
     * Cuenta los decimales de una cadena de precisión como "0.00100".
     */
    public static int decimalPlaces(String precision) {
        BigDecimal bd = new BigDecimal(precision).stripTrailingZeros();
        int scale = bd.scale();
        return Math.max(scale, 0);
    }

    public static String formatPrice(double price, String tickSize) {
        int places = decimalPlaces(tickSize);
        return String.format("%." + places + "f", roundToTickSize(price, tickSize));
    }

    public static String formatQty(double qty, String stepSize) {
        int places = decimalPlaces(stepSize);
        return String.format("%." + places + "f", roundToStepSize(qty, stepSize));
    }

    public static double pctChange(double from, double to) {
        if (from == 0) return 0;
        return (to - from) / from * 100.0;
    }
}
