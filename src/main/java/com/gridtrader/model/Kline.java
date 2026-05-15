package com.gridtrader.model;

public class Kline {

    private long openTime;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private long closeTime;

    public Kline() {}

    public Kline(long openTime, double open, double high, double low, double close, double volume) {
        this.openTime = openTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public double trueRange(double prevClose) {
        return Math.max(high - low,
               Math.max(Math.abs(high - prevClose),
                        Math.abs(low  - prevClose)));
    }

    public long getOpenTime()               { return openTime; }
    public void setOpenTime(long openTime)  { this.openTime = openTime; }

    public double getOpen()                 { return open; }
    public void setOpen(double open)        { this.open = open; }

    public double getHigh()                 { return high; }
    public void setHigh(double high)        { this.high = high; }

    public double getLow()                  { return low; }
    public void setLow(double low)          { this.low = low; }

    public double getClose()                { return close; }
    public void setClose(double close)      { this.close = close; }

    public double getVolume()               { return volume; }
    public void setVolume(double volume)    { this.volume = volume; }

    public long getCloseTime()              { return closeTime; }
    public void setCloseTime(long t)        { this.closeTime = t; }
}
