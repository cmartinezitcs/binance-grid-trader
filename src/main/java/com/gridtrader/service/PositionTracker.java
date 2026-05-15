package com.gridtrader.service;

import com.gridtrader.model.GridOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class PositionTracker {

    private static final Logger log = LoggerFactory.getLogger(PositionTracker.class);

    private final AtomicReference<Double> realizedPnl      = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalBuyValue    = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalSellValue   = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalBuyQty      = new AtomicReference<>(0.0);
    private final AtomicReference<Double> totalSellQty     = new AtomicReference<>(0.0);
    private final AtomicInteger           completedCycles  = new AtomicInteger(0);

    private volatile double initialInvestment;
    private volatile double currentPrice;
    private volatile double unrealizedPnl;

    public void setInitialInvestment(double amount) {
        this.initialInvestment = amount;
    }

    public void updateCurrentPrice(double price) {
        this.currentPrice = price;
    }

    public void recordBuy(GridOrder order) {
        double value = order.getExecutedQty() * order.getPrice();
        totalBuyValue.updateAndGet(v -> v + value);
        totalBuyQty.updateAndGet(v -> v + order.getExecutedQty());
        log.info("COMPRA registrada: qty={:.6f} @ {:.4f} = {:.2f} USDT",
            order.getExecutedQty(), order.getPrice(), value);
    }

    public void recordSell(GridOrder order, GridOrder pairedBuy) {
        double sellValue = order.getExecutedQty() * order.getPrice();
        double buyValue  = order.getExecutedQty() * pairedBuy.getPrice();
        double profit    = sellValue - buyValue;

        totalSellValue.updateAndGet(v -> v + sellValue);
        totalSellQty.updateAndGet(v -> v + order.getExecutedQty());
        realizedPnl.updateAndGet(v -> v + profit);
        completedCycles.incrementAndGet();

        log.info("VENTA registrada: qty={:.6f} @ {:.4f} | Ganancia ciclo: {:.4f} USDT | PnL total: {:.4f} USDT",
            order.getExecutedQty(), order.getPrice(), profit, realizedPnl.get());
    }

    public void recordSell(GridOrder order) {
        double sellValue = order.getExecutedQty() * order.getPrice();
        totalSellValue.updateAndGet(v -> v + sellValue);
        totalSellQty.updateAndGet(v -> v + order.getExecutedQty());
        log.info("VENTA registrada: qty={:.6f} @ {:.4f}", order.getExecutedQty(), order.getPrice());
    }

    public void updateUnrealizedPnl(double value) {
        this.unrealizedPnl = value;
    }

    public boolean isStopLossTriggered(double stopLossPct) {
        if (stopLossPct <= 0 || initialInvestment <= 0) return false;
        double loss = realizedPnl.get() + unrealizedPnl;
        return loss < -(initialInvestment * stopLossPct / 100.0);
    }

    public boolean isTakeProfitTriggered(double takeProfitPct) {
        if (takeProfitPct <= 0 || initialInvestment <= 0) return false;
        double gain = realizedPnl.get() + unrealizedPnl;
        return gain > initialInvestment * takeProfitPct / 100.0;
    }

    public double getRealizedPnl()      { return realizedPnl.get(); }
    public double getUnrealizedPnl()    { return unrealizedPnl; }
    public double getTotalPnl()         { return realizedPnl.get() + unrealizedPnl; }
    public double getTotalBuyValue()    { return totalBuyValue.get(); }
    public double getTotalSellValue()   { return totalSellValue.get(); }
    public double getTotalBuyQty()      { return totalBuyQty.get(); }
    public double getTotalSellQty()     { return totalSellQty.get(); }
    public int    getCompletedCycles()  { return completedCycles.get(); }
    public double getInitialInvestment(){ return initialInvestment; }
    public double getCurrentPrice()     { return currentPrice; }

    public double getPnlPercent() {
        if (initialInvestment <= 0) return 0;
        return getTotalPnl() / initialInvestment * 100.0;
    }
}
