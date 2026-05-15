package com.gridtrader.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GridLevel {

    public enum Status {
        IDLE,          // Sin orden activa
        BUY_PLACED,    // Orden de compra activa
        BOUGHT,        // Compra completada, esperando colocar venta
        SELL_PLACED,   // Orden de venta activa
        SOLD           // Ciclo completo
    }

    private int index;
    private double price;
    private double quantity;
    private Status status;
    private GridOrder activeOrder;
    private GridOrder lastBuyOrder;
    private double totalBought;
    private double totalSold;
    private int completedCycles;

    public GridLevel() {}

    public GridLevel(int index, double price, double quantity) {
        this.index = index;
        this.price = price;
        this.quantity = quantity;
        this.status = Status.IDLE;
        this.totalBought = 0;
        this.totalSold = 0;
        this.completedCycles = 0;
    }

    public double unrealizedPnl(double currentPrice) {
        if (status == Status.BOUGHT || status == Status.SELL_PLACED) {
            return (currentPrice - price) * quantity;
        }
        return 0;
    }

    // --- Getters y Setters ---

    public int getIndex()                   { return index; }
    public void setIndex(int index)         { this.index = index; }

    public double getPrice()                { return price; }
    public void setPrice(double price)      { this.price = price; }

    public double getQuantity()                 { return quantity; }
    public void setQuantity(double quantity)    { this.quantity = quantity; }

    public Status getStatus()               { return status; }
    public void setStatus(Status status)    { this.status = status; }

    public GridOrder getActiveOrder()                   { return activeOrder; }
    public void setActiveOrder(GridOrder activeOrder)   { this.activeOrder = activeOrder; }

    public GridOrder getLastBuyOrder()                  { return lastBuyOrder; }
    public void setLastBuyOrder(GridOrder order)        { this.lastBuyOrder = order; }

    public double getTotalBought()                  { return totalBought; }
    public void setTotalBought(double totalBought)  { this.totalBought = totalBought; }

    public double getTotalSold()                { return totalSold; }
    public void setTotalSold(double totalSold)  { this.totalSold = totalSold; }

    public int getCompletedCycles()                     { return completedCycles; }
    public void setCompletedCycles(int completedCycles) { this.completedCycles = completedCycles; }

    public void incrementCycles() { this.completedCycles++; }

    @Override
    public String toString() {
        return String.format("Level[%d] price=%.4f qty=%.6f status=%s", index, price, quantity, status);
    }
}
