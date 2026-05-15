package com.gridtrader.service;

import com.gridtrader.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gestiona el reinvestment automático de las ganancias realizadas.
 *
 * Cuando se alcanza el umbral configurado (por ciclos o por monto de PnL),
 * aumenta el capital efectivo del grid y actualiza las cantidades por nivel,
 * de modo que las siguientes órdenes operan con un presupuesto mayor.
 *
 * Los órdenes activos en ese momento NO se modifican; el nuevo capital
 * entra en juego cuando se colocan nuevas órdenes de compra.
 */
public class CompoundingManager {

    private static final Logger log = LoggerFactory.getLogger(CompoundingManager.class);

    public enum TriggerMode { CYCLES, PNL_AMOUNT }

    private final AppConfig config;

    private double effectiveCapital;       // presupuesto actual (inicial + reinvertido)
    private double pnlAtLastCompound;      // PnL realizado en el momento del último compound
    private int    cyclesAtLastCompound;   // ciclos en el momento del último compound
    private int    compoundingCount;       // número de veces que se ha compoundeado
    private double totalReinvested;        // USDT total reinvertido acumulado

    public CompoundingManager(AppConfig config) {
        this.config          = config;
        this.effectiveCapital = config.getTotalInvestment();
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Determina si es el momento de hacer compounding.
     * Solo retorna true si hay ganancia nueva desde el último compound.
     */
    public boolean shouldCompound(int totalCycles, double realizedPnl) {
        if (!config.isCompoundingEnabled()) return false;
        if (realizedPnl <= pnlAtLastCompound) return false;

        return switch (triggerMode()) {
            case CYCLES     -> (totalCycles - cyclesAtLastCompound) >= config.getCompoundingCyclesThreshold();
            case PNL_AMOUNT -> (realizedPnl - pnlAtLastCompound)   >= config.getCompoundingPnlThreshold();
        };
    }

    /**
     * Ejecuta el compound: registra el evento y aumenta el capital efectivo.
     * Llamar una sola vez por evento de compounding.
     */
    public void executeCompound(double realizedPnl, int totalCycles) {
        double newPnl     = realizedPnl - pnlAtLastCompound;
        double toReinvest = newPnl * (config.getCompoundingReinvestPercent() / 100.0);
        double prevCap    = effectiveCapital;

        effectiveCapital     += toReinvest;
        totalReinvested      += toReinvest;
        pnlAtLastCompound    = realizedPnl;
        cyclesAtLastCompound = totalCycles;
        compoundingCount++;

        log.info("╔══════════════════════════════════════════╗");
        log.info("║  COMPOUNDING #{}", compoundingCount);
        log.info("║  PnL nuevo:      +{:.4f} USDT", newPnl);
        log.info("║  Reinvertido:    +{:.4f} USDT ({:.0f}%)", toReinvest, config.getCompoundingReinvestPercent());
        log.info("║  Capital:  {:.2f} → {:.2f} USDT", prevCap, effectiveCapital);
        log.info("╚══════════════════════════════════════════╝");
    }

    /**
     * Calcula la cantidad (en activo base) para un nivel dado el capital efectivo actual.
     * Delegar TODA la lógica de cantidad aquí (también cuando compounding está desactivado).
     */
    public double computeQuantity(double levelPrice) {
        if (levelPrice <= 0) return 0;
        return (effectiveCapital / config.getGridLevels()) / levelPrice;
    }

    /**
     * Texto descriptivo de cuándo será el próximo compound (para el dashboard).
     */
    public String nextCompoundInfo(int totalCycles, double realizedPnl) {
        if (!config.isCompoundingEnabled()) return "desactivado";
        return switch (triggerMode()) {
            case CYCLES -> {
                int done = totalCycles - cyclesAtLastCompound;
                int need = config.getCompoundingCyclesThreshold();
                yield done >= need
                    ? "¡listo!"
                    : String.format("%d/%d ciclos", done, need);
            }
            case PNL_AMOUNT -> {
                double done = realizedPnl - pnlAtLastCompound;
                double need = config.getCompoundingPnlThreshold();
                yield done >= need
                    ? "¡listo!"
                    : String.format("+%.2f/%.2f$", done, need);
            }
        };
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public double getEffectiveCapital()   { return effectiveCapital; }
    public int    getCompoundingCount()   { return compoundingCount; }
    public double getTotalReinvested()    { return totalReinvested; }
    public double getPnlAtLastCompound()  { return pnlAtLastCompound; }
    public int    getCyclesAtLastCompound(){ return cyclesAtLastCompound; }
    public boolean isEnabled()            { return config.isCompoundingEnabled(); }

    public double growthPercent() {
        double initial = config.getTotalInvestment();
        if (initial <= 0) return 0;
        return (effectiveCapital - initial) / initial * 100.0;
    }

    private TriggerMode triggerMode() {
        return TriggerMode.valueOf(config.getCompoundingTrigger());
    }
}
