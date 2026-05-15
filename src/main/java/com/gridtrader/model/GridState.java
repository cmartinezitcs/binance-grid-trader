package com.gridtrader.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GridState {

    private String symbol;
    private double initialPrice;
    private double lowerBound;
    private double upperBound;
    private double lastAtr;
    private double totalInvested;
    private double realizedPnl;
    private int totalCycles;
    private long startTime;
    private long lastUpdateTime;
    private List<GridLevel> levels = new ArrayList<>();

    public GridState() {}

    public String getSymbol()                   { return symbol; }
    public void setSymbol(String symbol)        { this.symbol = symbol; }

    public double getInitialPrice()             { return initialPrice; }
    public void setInitialPrice(double p)       { this.initialPrice = p; }

    public double getLowerBound()               { return lowerBound; }
    public void setLowerBound(double lb)        { this.lowerBound = lb; }

    public double getUpperBound()               { return upperBound; }
    public void setUpperBound(double ub)        { this.upperBound = ub; }

    public double getLastAtr()                  { return lastAtr; }
    public void setLastAtr(double atr)          { this.lastAtr = atr; }

    public double getTotalInvested()            { return totalInvested; }
    public void setTotalInvested(double ti)     { this.totalInvested = ti; }

    public double getRealizedPnl()              { return realizedPnl; }
    public void setRealizedPnl(double pnl)      { this.realizedPnl = pnl; }

    public int getTotalCycles()                 { return totalCycles; }
    public void setTotalCycles(int tc)          { this.totalCycles = tc; }

    public long getStartTime()                  { return startTime; }
    public void setStartTime(long t)            { this.startTime = t; }

    public long getLastUpdateTime()             { return lastUpdateTime; }
    public void setLastUpdateTime(long t)       { this.lastUpdateTime = t; }

    public List<GridLevel> getLevels()          { return levels; }
    public void setLevels(List<GridLevel> l)    { this.levels = l; }
}
