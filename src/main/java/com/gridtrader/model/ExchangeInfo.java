package com.gridtrader.model;

public class ExchangeInfo {

    private String symbol;
    private String tickSize;
    private String stepSize;
    private double minQty;
    private double minNotional;
    private int pricePrecision;
    private int quantityPrecision;

    public ExchangeInfo() {}

    public ExchangeInfo(String symbol, String tickSize, String stepSize,
                        double minQty, double minNotional) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.stepSize = stepSize;
        this.minQty = minQty;
        this.minNotional = minNotional;
    }

    public boolean isValidOrder(double price, double quantity) {
        return quantity >= minQty && (price * quantity) >= minNotional;
    }

    public String getSymbol()               { return symbol; }
    public void setSymbol(String symbol)    { this.symbol = symbol; }

    public String getTickSize()             { return tickSize; }
    public void setTickSize(String ts)      { this.tickSize = ts; }

    public String getStepSize()             { return stepSize; }
    public void setStepSize(String ss)      { this.stepSize = ss; }

    public double getMinQty()               { return minQty; }
    public void setMinQty(double mq)        { this.minQty = mq; }

    public double getMinNotional()          { return minNotional; }
    public void setMinNotional(double mn)   { this.minNotional = mn; }

    public int getPricePrecision()          { return pricePrecision; }
    public void setPricePrecision(int pp)   { this.pricePrecision = pp; }

    public int getQuantityPrecision()       { return quantityPrecision; }
    public void setQuantityPrecision(int qp){ this.quantityPrecision = qp; }

    @Override
    public String toString() {
        return String.format("ExchangeInfo{symbol=%s, tickSize=%s, stepSize=%s, minQty=%s, minNotional=%.2f}",
            symbol, tickSize, stepSize, minQty, minNotional);
    }
}
