package com.gridtrader.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GridOrder {

    public enum Side { BUY, SELL }

    public enum Status {
        NEW, PARTIALLY_FILLED, FILLED, CANCELED, PENDING_CANCEL, REJECTED, EXPIRED, UNKNOWN
    }

    private long orderId;
    private String clientOrderId;
    private String symbol;
    private Side side;
    private Status status;
    private double price;
    private double origQty;
    private double executedQty;
    private long createTime;
    private long updateTime;
    private int levelIndex;

    public GridOrder() {}

    public GridOrder(long orderId, String symbol, Side side, double price, double origQty) {
        this.orderId = orderId;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.origQty = origQty;
        this.executedQty = 0;
        this.status = Status.NEW;
        this.createTime = System.currentTimeMillis();
    }

    public boolean isFilled() {
        return status == Status.FILLED;
    }

    public boolean isActive() {
        return status == Status.NEW || status == Status.PARTIALLY_FILLED;
    }

    public double getNotional() {
        return price * origQty;
    }

    // --- Getters y Setters ---

    public long getOrderId()                { return orderId; }
    public void setOrderId(long orderId)    { this.orderId = orderId; }

    public String getClientOrderId()                        { return clientOrderId; }
    public void setClientOrderId(String clientOrderId)      { this.clientOrderId = clientOrderId; }

    public String getSymbol()               { return symbol; }
    public void setSymbol(String symbol)    { this.symbol = symbol; }

    public Side getSide()                   { return side; }
    public void setSide(Side side)          { this.side = side; }

    public Status getStatus()               { return status; }
    public void setStatus(Status status)    { this.status = status; }

    public double getPrice()                { return price; }
    public void setPrice(double price)      { this.price = price; }

    public double getOrigQty()              { return origQty; }
    public void setOrigQty(double origQty)  { this.origQty = origQty; }

    public double getExecutedQty()                  { return executedQty; }
    public void setExecutedQty(double executedQty)  { this.executedQty = executedQty; }

    public long getCreateTime()                 { return createTime; }
    public void setCreateTime(long createTime)  { this.createTime = createTime; }

    public long getUpdateTime()                 { return updateTime; }
    public void setUpdateTime(long updateTime)  { this.updateTime = updateTime; }

    public int getLevelIndex()                  { return levelIndex; }
    public void setLevelIndex(int levelIndex)   { this.levelIndex = levelIndex; }

    @Override
    public String toString() {
        return String.format("GridOrder{id=%d, side=%s, price=%.4f, qty=%.6f, status=%s}",
            orderId, side, price, origQty, status);
    }
}
