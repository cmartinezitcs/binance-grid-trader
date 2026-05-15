package com.gridtrader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    // API
    private String apiKey;
    private String apiSecret;
    private boolean testnet;

    // Symbol
    private String symbol;

    // Grid
    private int gridLevels;
    private double totalInvestment;
    private String spacingType;
    private double spacingPercent;

    // Dynamic Range
    private boolean dynamicRangeEnabled;
    private int atrPeriod;
    private String atrInterval;
    private double atrMultiplier;
    private double rebalanceThreshold;

    // Risk
    private double stopLossPercent;
    private double takeProfitPercent;
    private int maxActiveOrders;

    // Intervals (seconds)
    private int orderCheckInterval;
    private int monitorInterval;
    private int rangeCheckInterval;
    private long apiRateLimitDelay;

    // Persistence
    private String stateFile;

    // Compounding
    private boolean compoundingEnabled;
    private String  compoundingTrigger;
    private int     compoundingCyclesThreshold;
    private double  compoundingPnlThreshold;
    private double  compoundingReinvestPercent;

    // Trend Filter
    private boolean trendFilterEnabled;
    private int     trendFastPeriod;
    private int     trendSlowPeriod;
    private String  trendInterval;
    private int     trendCheckInterval;

    // Entry Filter (RSI + Bollinger Bands)
    private boolean entryFilterEnabled;
    private String  entryFilterInterval;
    private int     entryFilterRsiPeriod;
    private double  entryFilterRsiOversold;
    private double  entryFilterRsiOverbought;
    private int     entryFilterBbPeriod;
    private double  entryFilterBbStdDev;
    private double  entryFilterBbThreshold;
    private boolean entryFilterRequireBoth;
    private int     entryFilterCheckInterval;

    // Mode
    private boolean dryRun;

    private AppConfig() {}

    public static AppConfig load(String path) {
        Properties props = new Properties();

        // Prefer external file, fall back to classpath
        if (Files.exists(Paths.get(path))) {
            try (FileInputStream fis = new FileInputStream(path)) {
                props.load(fis);
                log.info("Config loaded from external file: {}", path);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config from " + path, e);
            }
        } else {
            try (InputStream is = AppConfig.class.getClassLoader().getResourceAsStream("config.properties")) {
                if (is == null) {
                    throw new RuntimeException("Config file not found: " + path);
                }
                props.load(is);
                log.info("Config loaded from classpath: config.properties");
            } catch (IOException e) {
                throw new RuntimeException("Failed to load config from classpath", e);
            }
        }

        AppConfig cfg = new AppConfig();

        cfg.apiKey              = require(props, "binance.api.key");
        cfg.apiSecret           = require(props, "binance.api.secret");
        cfg.testnet             = Boolean.parseBoolean(props.getProperty("binance.testnet", "true"));

        cfg.symbol              = require(props, "grid.symbol").toUpperCase();

        cfg.gridLevels          = Integer.parseInt(require(props, "grid.levels"));
        cfg.totalInvestment     = Double.parseDouble(require(props, "grid.investment.total"));
        cfg.spacingType         = props.getProperty("grid.spacing.type", "GEOMETRIC").toUpperCase();
        cfg.spacingPercent      = Double.parseDouble(props.getProperty("grid.spacing.percent", "1.0"));

        cfg.dynamicRangeEnabled = Boolean.parseBoolean(props.getProperty("grid.dynamic.range.enabled", "true"));
        cfg.atrPeriod           = Integer.parseInt(props.getProperty("grid.atr.period", "14"));
        cfg.atrInterval         = props.getProperty("grid.atr.interval", "1h");
        cfg.atrMultiplier       = Double.parseDouble(props.getProperty("grid.atr.multiplier", "2.0"));
        cfg.rebalanceThreshold  = Double.parseDouble(props.getProperty("grid.rebalance.threshold", "0.7"));

        cfg.stopLossPercent     = Double.parseDouble(props.getProperty("grid.stop.loss.percent", "5.0"));
        cfg.takeProfitPercent   = Double.parseDouble(props.getProperty("grid.take.profit.percent", "20.0"));
        cfg.maxActiveOrders     = Integer.parseInt(props.getProperty("grid.max.active.orders", "20"));

        cfg.orderCheckInterval  = Integer.parseInt(props.getProperty("grid.order.check.interval", "15"));
        cfg.monitorInterval     = Integer.parseInt(props.getProperty("grid.monitor.interval", "10"));
        cfg.rangeCheckInterval  = Integer.parseInt(props.getProperty("grid.range.check.interval", "300"));
        cfg.apiRateLimitDelay   = Long.parseLong(props.getProperty("grid.api.rate.limit.delay", "200"));

        cfg.stateFile           = props.getProperty("grid.state.file", "grid-state.json");
        cfg.dryRun              = Boolean.parseBoolean(props.getProperty("grid.dry.run", "true"));

        cfg.compoundingEnabled          = Boolean.parseBoolean(props.getProperty("grid.compounding.enabled", "false"));
        cfg.compoundingTrigger          = props.getProperty("grid.compounding.trigger", "CYCLES").toUpperCase();
        cfg.compoundingCyclesThreshold  = Integer.parseInt(props.getProperty("grid.compounding.cycles.threshold", "5"));
        cfg.compoundingPnlThreshold     = Double.parseDouble(props.getProperty("grid.compounding.pnl.threshold", "10.0"));
        cfg.compoundingReinvestPercent  = Double.parseDouble(props.getProperty("grid.compounding.reinvest.percent", "100.0"));

        cfg.trendFilterEnabled  = Boolean.parseBoolean(props.getProperty("grid.trend.filter.enabled", "false"));
        cfg.trendFastPeriod     = Integer.parseInt(props.getProperty("grid.trend.filter.fast.period", "50"));
        cfg.trendSlowPeriod     = Integer.parseInt(props.getProperty("grid.trend.filter.slow.period", "200"));
        cfg.trendInterval       = props.getProperty("grid.trend.filter.interval", "1h");
        cfg.trendCheckInterval  = Integer.parseInt(props.getProperty("grid.trend.filter.check.interval", "300"));

        cfg.entryFilterEnabled       = Boolean.parseBoolean(props.getProperty("grid.entry.filter.enabled", "false"));
        cfg.entryFilterInterval      = props.getProperty("grid.entry.filter.interval", "1h");
        cfg.entryFilterRsiPeriod     = Integer.parseInt(props.getProperty("grid.entry.filter.rsi.period", "14"));
        cfg.entryFilterRsiOversold   = Double.parseDouble(props.getProperty("grid.entry.filter.rsi.oversold", "35.0"));
        cfg.entryFilterRsiOverbought = Double.parseDouble(props.getProperty("grid.entry.filter.rsi.overbought", "65.0"));
        cfg.entryFilterBbPeriod      = Integer.parseInt(props.getProperty("grid.entry.filter.bb.period", "20"));
        cfg.entryFilterBbStdDev      = Double.parseDouble(props.getProperty("grid.entry.filter.bb.std.dev", "2.0"));
        cfg.entryFilterBbThreshold   = Double.parseDouble(props.getProperty("grid.entry.filter.bb.threshold", "0.3"));
        cfg.entryFilterRequireBoth   = Boolean.parseBoolean(props.getProperty("grid.entry.filter.require.both", "false"));
        cfg.entryFilterCheckInterval = Integer.parseInt(props.getProperty("grid.entry.filter.check.interval", "300"));

        cfg.validate();
        return cfg;
    }

    private void validate() {
        if (apiKey.startsWith("TU_") || apiKey.isBlank()) {
            throw new IllegalStateException("binance.api.key no configurado en config.properties");
        }
        if (gridLevels < 2 || gridLevels > 100) {
            throw new IllegalStateException("grid.levels debe estar entre 2 y 100");
        }
        if (totalInvestment <= 0) {
            throw new IllegalStateException("grid.investment.total debe ser mayor que 0");
        }
        if (!spacingType.equals("ARITHMETIC") && !spacingType.equals("GEOMETRIC")) {
            throw new IllegalStateException("grid.spacing.type debe ser ARITHMETIC o GEOMETRIC");
        }
        if (atrMultiplier <= 0) {
            throw new IllegalStateException("grid.atr.multiplier debe ser mayor que 0");
        }
        if (rebalanceThreshold <= 0 || rebalanceThreshold >= 1) {
            throw new IllegalStateException("grid.rebalance.threshold debe estar entre 0 y 1 (exclusivo)");
        }
    }

    private static String require(Properties props, String key) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Configuración requerida faltante: " + key);
        }
        return val.trim();
    }

    // --- Getters ---

    public String getApiKey()              { return apiKey; }
    public String getApiSecret()           { return apiSecret; }
    public boolean isTestnet()             { return testnet; }
    public String getSymbol()              { return symbol; }
    public int getGridLevels()             { return gridLevels; }
    public double getTotalInvestment()     { return totalInvestment; }
    public String getSpacingType()         { return spacingType; }
    public double getSpacingPercent()      { return spacingPercent; }
    public boolean isDynamicRangeEnabled() { return dynamicRangeEnabled; }
    public int getAtrPeriod()              { return atrPeriod; }
    public String getAtrInterval()         { return atrInterval; }
    public double getAtrMultiplier()       { return atrMultiplier; }
    public double getRebalanceThreshold()  { return rebalanceThreshold; }
    public double getStopLossPercent()     { return stopLossPercent; }
    public double getTakeProfitPercent()   { return takeProfitPercent; }
    public int getMaxActiveOrders()        { return maxActiveOrders; }
    public int getOrderCheckInterval()     { return orderCheckInterval; }
    public int getMonitorInterval()        { return monitorInterval; }
    public int getRangeCheckInterval()     { return rangeCheckInterval; }
    public long getApiRateLimitDelay()     { return apiRateLimitDelay; }
    public String getStateFile()           { return stateFile; }
    public boolean isDryRun()              { return dryRun; }
    public boolean isCompoundingEnabled()           { return compoundingEnabled; }
    public String  getCompoundingTrigger()          { return compoundingTrigger; }
    public int     getCompoundingCyclesThreshold()  { return compoundingCyclesThreshold; }
    public double  getCompoundingPnlThreshold()     { return compoundingPnlThreshold; }
    public double  getCompoundingReinvestPercent()  { return compoundingReinvestPercent; }
    public boolean isTrendFilterEnabled()  { return trendFilterEnabled; }
    public int getTrendFastPeriod()        { return trendFastPeriod; }
    public int getTrendSlowPeriod()        { return trendSlowPeriod; }
    public String getTrendInterval()       { return trendInterval; }
    public int getTrendCheckInterval()     { return trendCheckInterval; }
    public boolean isEntryFilterEnabled()       { return entryFilterEnabled; }
    public String  getEntryFilterInterval()     { return entryFilterInterval; }
    public int     getEntryFilterRsiPeriod()    { return entryFilterRsiPeriod; }
    public double  getEntryFilterRsiOversold()  { return entryFilterRsiOversold; }
    public double  getEntryFilterRsiOverbought(){ return entryFilterRsiOverbought; }
    public int     getEntryFilterBbPeriod()     { return entryFilterBbPeriod; }
    public double  getEntryFilterBbStdDev()     { return entryFilterBbStdDev; }
    public double  getEntryFilterBbThreshold()  { return entryFilterBbThreshold; }
    public boolean isEntryFilterRequireBoth()   { return entryFilterRequireBoth; }
    public int     getEntryFilterCheckInterval(){ return entryFilterCheckInterval; }

    @Override
    public String toString() {
        return String.format(
            "AppConfig{symbol='%s', levels=%d, investment=%.2f, dynamic=%b, atrPeriod=%d, " +
            "atrMult=%.1f, testnet=%b, dryRun=%b}",
            symbol, gridLevels, totalInvestment, dynamicRangeEnabled,
            atrPeriod, atrMultiplier, testnet, dryRun
        );
    }
}
