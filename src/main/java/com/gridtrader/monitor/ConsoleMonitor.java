package com.gridtrader.monitor;

import com.gridtrader.config.AppConfig;
import com.gridtrader.model.GridLevel;
import com.gridtrader.service.PositionTracker;
import com.gridtrader.service.CompoundingManager;
import com.gridtrader.strategy.DynamicRangeManager;
import com.gridtrader.strategy.EntryFilter;
import com.gridtrader.strategy.TrendFilter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class ConsoleMonitor {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Ancho interior (entre los dos ║). Todas las filas deben tener exactamente W chars.
    private static final int W = 64;

    private final AppConfig           config;
    private final PositionTracker     tracker;
    private final DynamicRangeManager rangeManager;
    private final TrendFilter         trendFilter;
    private final EntryFilter         entryFilter;
    private final CompoundingManager  compounding;
    private final long                startTime = System.currentTimeMillis();

    public ConsoleMonitor(AppConfig config, PositionTracker tracker,
                          DynamicRangeManager rangeManager, TrendFilter trendFilter,
                          EntryFilter entryFilter, CompoundingManager compounding) {
        this.config       = config;
        this.tracker      = tracker;
        this.rangeManager = rangeManager;
        this.trendFilter  = trendFilter;
        this.entryFilter  = entryFilter;
        this.compounding  = compounding;
    }

    public void render(List<GridLevel> levels, double currentPrice) {
        StringBuilder sb = new StringBuilder();

        // Limpiar pantalla con ANSI
        sb.append("\033[H\033[2J");

        String now  = LocalDateTime.now().format(TIME_FMT);
        String mode = config.isDryRun() ? "DRY-RUN"
                    : config.isTestnet() ? "TESTNET" : "LIVE   ";

        // ── Cabecera ────────────────────────────────────────────────────────
        sb.append(top()).append('\n');
        sb.append(row(titleLine(config.getSymbol(), now))).append('\n');
        sb.append(sep()).append('\n');

        // ── Mercado ─────────────────────────────────────────────────────────
        sb.append(row(f("  Precio actual: %12.2f USDT   Modo: %-14s",
                currentPrice, mode))).append('\n');

        sb.append(row(f("  Rango:  [ %12.2f  —  %12.2f ]  ATR: %8.2f",
                rangeManager.getLowerBound(),
                rangeManager.getUpperBound(),
                rangeManager.getLastAtr()))).append('\n');

        sb.append(row(f("  Niveles: %-3d  Espaciado: %-12s  Dinamico: %-3s",
                config.getGridLevels(),
                config.getSpacingType(),
                config.isDynamicRangeEnabled() ? "Si" : "No"))).append('\n');

        // Filas de tendencia (solo si el filtro está activo)
        if (trendFilter.isEnabled()) {
            TrendFilter.Trend trend = trendFilter.getTrend();
            String trendTag = switch (trend) {
                case BULLISH -> "** ALCISTA **";
                case BEARISH -> "!! BAJISTA !!";
                case NEUTRAL -> "-- LATERAL --";
            };
            String buysState = trendFilter.allowBuy() ? "activas" : "PAUSADAS";
            sb.append(row(f("  Tendencia: %-13s  MA%d: %8.2f  MA%d: %8.2f",
                    trendTag,
                    trendFilter.getFastPeriod(), trendFilter.getFastMa(),
                    trendFilter.getSlowPeriod(), trendFilter.getSlowMa()))).append('\n');
            sb.append(row(f("  Compras: %-10s", buysState))).append('\n');
        }

        // Filas de filtro de entrada RSI+BB (solo si está activo)
        if (entryFilter.isEnabled()) {
            EntryFilter.Signal signal = entryFilter.getSignal();
            String signalTag = switch (signal) {
                case FAVORABLE   -> "** FAVORABLE  **";
                case UNFAVORABLE -> "!! NO OPERAR  !!";
                case NEUTRAL     -> "-- NEUTRO     --";
            };
            String rsiLabel = entryFilter.getRsi() < entryFilter.getRsiOversold()   ? "sobrevendido"
                            : entryFilter.getRsi() > entryFilter.getRsiOverbought()  ? "sobrecomprado"
                            : "neutral";
            sb.append(row(f("  RSI(%d): %5.1f %-13s  BB%%B: %5.3f",
                    entryFilter.getRsiPeriod(), entryFilter.getRsi(), rsiLabel,
                    entryFilter.getBbPercent()))).append('\n');
            sb.append(row(f("  Entrada: %-16s", signalTag))).append('\n');
        }

        sb.append(sep()).append('\n');

        // ── P&L ─────────────────────────────────────────────────────────────
        double rpnl = tracker.getRealizedPnl();
        double upnl = tracker.getUnrealizedPnl();
        double pct  = tracker.getPnlPercent();

        sb.append(row(f("  PnL Realizado: %+11.4f USDT    Ciclos:  %-8d",
                rpnl, tracker.getCompletedCycles()))).append('\n');

        sb.append(row(f("  PnL No Real.:  %+11.4f USDT    ROI:     %+.2f%%",
                upnl, pct))).append('\n');

        sb.append(row(f("  Capital usado: %11.2f USDT    Tiempo:  %-10s",
                tracker.getTotalBuyValue(), uptime()))).append('\n');

        // Filas de compounding (solo si está activo)
        if (compounding.isEnabled()) {
            String nextInfo = compounding.nextCompoundInfo(
                tracker.getCompletedCycles(), tracker.getRealizedPnl());
            sb.append(row(f("  Efectivo: %10.2f USDT (+%.2f%%)  Compounds: %-3d",
                    compounding.getEffectiveCapital(),
                    compounding.growthPercent(),
                    compounding.getCompoundingCount()))).append('\n');
            sb.append(row(f("  Proximo compound: %-20s", nextInfo))).append('\n');
        }

        sb.append(sep()).append('\n');

        // ── Tabla de niveles ─────────────────────────────────────────────────
        sb.append(row(f("  %-5s  %-14s  %-15s  %-12s  ", "#", "Precio", "Estado", "Orden"))).append('\n');
        sb.append(thin()).append('\n');

        double levelStep = (rangeManager.getUpperBound() - rangeManager.getLowerBound())
                           / Math.max(1, config.getGridLevels());

        int maxDisplay = Math.min(levels.size(), 18);
        int from = levels.size() - 1;
        int to   = Math.max(0, from - maxDisplay + 1);

        for (int i = from; i >= to; i--) {
            GridLevel lvl   = levels.get(i);
            boolean current = Math.abs(lvl.getPrice() - currentPrice) <= levelStep * 0.5;
            String  arrow   = current ? " ►" : "  ";
            String  orderId = lvl.getActiveOrder() != null
                    ? "#" + (lvl.getActiveOrder().getOrderId() % 100_000) : "";

            sb.append(row(f("  [%2d]  %14.4f  %-15s  %-12s%s",
                    lvl.getIndex(),
                    lvl.getPrice(),
                    statusLabel(lvl),
                    orderId,
                    arrow))).append('\n');
        }

        if (levels.size() > maxDisplay) {
            sb.append(row(f("  ... y %d niveles mas ...", levels.size() - maxDisplay))).append('\n');
        }

        sb.append(bot()).append('\n');
        sb.append("  [Ctrl+C] Detener bot y cancelar ordenes\n");

        System.out.print(sb);
    }

    // ── Helpers de layout ────────────────────────────────────────────────────

    /** Centra el título entre el símbolo (izq.) y la hora (der.). */
    private String titleLine(String symbol, String time) {
        String center = "BINANCE GRID TRADER";
        // "  " + symbol + gaps + center + gaps + time + "  " = W
        int gaps = W - 2 - symbol.length() - center.length() - time.length() - 2;
        int left  = Math.max(1, gaps / 2);
        int right = Math.max(1, gaps - left);
        return "  " + symbol + " ".repeat(left) + center + " ".repeat(right) + time + "  ";
    }

    /** Envuelve contenido en ║...║, garantizando exactamente W chars interiores. */
    private String row(String content) {
        int len = content.length();
        if (len > W) {
            content = content.substring(0, W);
        } else if (len < W) {
            content = content + " ".repeat(W - len);
        }
        return "║" + content + "║";
    }

    /** String.format con Locale.US para separador decimal consistente. */
    private String f(String fmt, Object... args) {
        return String.format(Locale.US, fmt, args);
    }

    private String top()  { return "╔" + "═".repeat(W) + "╗"; }
    private String sep()  { return "╠" + "═".repeat(W) + "╣"; }
    private String thin() { return "║" + "─".repeat(W) + "║"; }
    private String bot()  { return "╚" + "═".repeat(W) + "╝"; }

    private String statusLabel(GridLevel level) {
        return switch (level.getStatus()) {
            case IDLE        -> "- sin orden    ";
            case BUY_PLACED  -> "v esperando    ";
            case BOUGHT      -> "* comprado     ";
            case SELL_PLACED -> "^ vendiendo    ";
            case SOLD        -> "* vendido      ";
        };
    }

    private String uptime() {
        long secs = (System.currentTimeMillis() - startTime) / 1000;
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }
}
