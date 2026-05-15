package com.gridtrader.strategy;

import com.gridtrader.api.BinanceRestClient;
import com.gridtrader.config.AppConfig;
import com.gridtrader.model.ExchangeInfo;
import com.gridtrader.model.GridLevel;
import com.gridtrader.model.GridOrder;
import com.gridtrader.model.GridState;
import com.gridtrader.monitor.ConsoleMonitor;
import com.gridtrader.service.CompoundingManager;
import com.gridtrader.service.PositionTracker;
import com.gridtrader.service.StateManager;
import com.gridtrader.util.NumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class GridStrategy {

    private static final Logger log = LoggerFactory.getLogger(GridStrategy.class);

    private final AppConfig           config;
    private final BinanceRestClient   client;
    private final DynamicRangeManager rangeManager;
    private final TrendFilter         trendFilter;
    private final EntryFilter         entryFilter;
    private final CompoundingManager  compounding;
    private final PositionTracker     tracker;
    private final StateManager        stateManager;
    private final ConsoleMonitor      monitor;

    private List<GridLevel>           levels;
    private ExchangeInfo              exchangeInfo;

    private volatile boolean          running;
    private volatile double           currentPrice;
    private volatile boolean          pendingRebalance = false;

    private ScheduledExecutorService  scheduler;

    public GridStrategy(AppConfig config) {
        this.config       = config;
        this.client       = new BinanceRestClient(config);
        this.rangeManager = new DynamicRangeManager(config, client);
        this.trendFilter  = new TrendFilter(config, client);
        this.entryFilter  = new EntryFilter(config, client);
        this.compounding  = new CompoundingManager(config);
        this.tracker      = new PositionTracker();
        this.stateManager = new StateManager(config.getStateFile());
        this.monitor      = new ConsoleMonitor(config, tracker, rangeManager, trendFilter, entryFilter, compounding);
        this.levels       = new ArrayList<>();
    }

    // -------------------------------------------------------
    //  CICLO DE VIDA
    // -------------------------------------------------------

    public void start() {
        log.info("======================================================");
        log.info("  BINANCE GRID TRADER arrancando...");
        log.info("  Símbolo: {} | Niveles: {} | Inversión: {} USDT",
            config.getSymbol(), config.getGridLevels(), config.getTotalInvestment());
        log.info("  Modo: {} | Testnet: {}",
            config.isDryRun() ? "DRY-RUN (sin órdenes reales)" : "LIVE",
            config.isTestnet());
        log.info("======================================================");

        // 1. Cargar info del exchange (tick size, step size, etc.)
        exchangeInfo = client.getExchangeInfo(config.getSymbol());
        log.info("Exchange info: {}", exchangeInfo);

        // 2. Obtener precio actual
        currentPrice = client.getCurrentPrice(config.getSymbol());
        log.info("Precio actual de {}: {}", config.getSymbol(), currentPrice);

        tracker.setInitialInvestment(config.getTotalInvestment());
        tracker.updateCurrentPrice(currentPrice);

        // 3. Evaluar tendencia inicial
        TrendFilter.Trend initialTrend = trendFilter.refresh(currentPrice);
        if (config.isTrendFilterEnabled()) {
            log.info("Filtro de tendencia: {} | MA{}: {:.2f} | MA{}: {:.2f}",
                initialTrend.label(),
                config.getTrendFastPeriod(), trendFilter.getFastMa(),
                config.getTrendSlowPeriod(), trendFilter.getSlowMa());
            if (initialTrend == TrendFilter.Trend.BEARISH) {
                log.warn("Tendencia BAJISTA detectada — se suspenden nuevas compras hasta que cambie");
            }
        }

        // 3b. Evaluar filtro RSI+BB inicial
        if (config.isEntryFilterEnabled()) {
            EntryFilter.Signal signal = entryFilter.evaluate(currentPrice);
            log.info("Filtro de entrada (RSI+BB): {} | RSI({})={:.1f} | BB%B={:.3f}",
                signal, config.getEntryFilterRsiPeriod(),
                entryFilter.getRsi(), entryFilter.getBbPercent());
        }

        // 4. Intentar recuperar estado previo
        GridState savedState = stateManager.load();
        if (savedState != null && savedState.getSymbol().equals(config.getSymbol())) {
            restoreFromState(savedState);
        } else {
            initializeGrid();
        }

        // 4. Iniciar tareas programadas
        running = true;
        scheduler = Executors.newScheduledThreadPool(3,
            r -> { Thread t = new Thread(r, "grid-scheduler"); t.setDaemon(true); return t; });

        scheduler.scheduleAtFixedRate(
            this::checkOrdersFills,
            5, config.getOrderCheckInterval(), TimeUnit.SECONDS
        );
        scheduler.scheduleAtFixedRate(
            this::checkRangeAndRebalance,
            config.getRangeCheckInterval(), config.getRangeCheckInterval(), TimeUnit.SECONDS
        );
        scheduler.scheduleAtFixedRate(
            this::refreshDisplay,
            2, config.getMonitorInterval(), TimeUnit.SECONDS
        );

        log.info("Grid iniciado con {} niveles entre {:.4f} y {:.4f}",
            levels.size(), rangeManager.getLowerBound(), rangeManager.getUpperBound());

        // Mantener hilo principal vivo
        awaitShutdown();
    }

    public void shutdown() {
        if (!running) return;
        running = false;

        log.info("Apagando Grid Trader...");
        if (scheduler != null) {
            scheduler.shutdown();
            try { scheduler.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) {}
        }

        if (!config.isDryRun()) {
            log.info("Cancelando todas las órdenes abiertas...");
            try { client.cancelAllOrders(config.getSymbol()); }
            catch (Exception e) { log.error("Error cancelando órdenes: {}", e.getMessage()); }
        }

        saveState();
        log.info("Grid detenido. PnL realizado: {:.4f} USDT | Ciclos: {}",
            tracker.getRealizedPnl(), tracker.getCompletedCycles());
    }

    // -------------------------------------------------------
    //  INICIALIZACIÓN DEL GRID
    // -------------------------------------------------------

    private void initializeGrid() {
        double[] range = rangeManager.calculateRange(currentPrice);
        double lower = range[0];
        double upper = range[1];

        double[] priceLevels = computePriceLevels(lower, upper);
        levels = new ArrayList<>();

        for (int i = 0; i < priceLevels.length; i++) {
            double qty = computeQuantity(priceLevels[i]);
            GridLevel level = new GridLevel(i, priceLevels[i], qty);
            levels.add(level);
        }

        // Colocar órdenes de compra en todos los niveles por debajo del precio actual
        // El filtro de tendencia puede bloquear las compras si hay tendencia bajista
        int ordersPlaced = 0;
        for (GridLevel level : levels) {
            if (level.getPrice() < currentPrice && ordersPlaced < config.getMaxActiveOrders()) {
                if (trendFilter.allowBuy()) {
                    placeBuyOrder(level);
                    ordersPlaced++;
                } else {
                    level.setStatus(GridLevel.Status.IDLE);
                }
            }
        }

        log.info("Grid inicializado: {} niveles | {} órdenes de compra colocadas", levels.size(), ordersPlaced);
        saveState();
    }

    private void restoreFromState(GridState state) {
        log.info("Restaurando grid desde estado previo ({} niveles)", state.getLevels().size());
        this.levels = state.getLevels();

        if (!config.isDryRun()) {
            // Los IDs dry-run son generados localmente (empiezan en 1_000_001).
            // Si el estado fue guardado en modo dry-run, esas órdenes no existen
            // en Binance y hay que descartar sus referencias antes de sincronizar.
            int cleared = 0;
            for (GridLevel level : levels) {
                if (level.getActiveOrder() != null
                        && level.getActiveOrder().getOrderId() <= 2_000_000) {
                    log.warn("Nivel {}: orden dry-run #{} descartada (no existe en Binance)",
                        level.getIndex(), level.getActiveOrder().getOrderId());
                    level.setActiveOrder(null);
                    level.setStatus(GridLevel.Status.IDLE);
                    cleared++;
                }
            }
            if (cleared > 0) {
                log.warn("{} órdenes dry-run descartadas — el grid se reiniciará limpio", cleared);
                stateManager.delete();
                initializeGrid();
                return;
            }
            syncWithOpenOrders();
        }
    }

    // -------------------------------------------------------
    //  VERIFICACIÓN DE ÓRDENES COMPLETADAS
    // -------------------------------------------------------

    private synchronized void checkOrdersFills() {
        try {
            currentPrice = client.getCurrentPrice(config.getSymbol());
            tracker.updateCurrentPrice(currentPrice);

            double upnl = 0;
            for (GridLevel level : levels) {
                if (level.getStatus() == GridLevel.Status.BOUGHT) {
                    upnl += (currentPrice - level.getPrice()) * level.getQuantity();
                }
            }
            tracker.updateUnrealizedPnl(upnl);

            for (GridLevel level : levels) {
                if (level.getActiveOrder() == null) continue;

                GridOrder order;
                try {
                    if (config.isDryRun()) {
                        order = simulateOrderCheck(level);
                    } else {
                        order = client.getOrder(config.getSymbol(), level.getActiveOrder().getOrderId());
                    }
                } catch (Exception e) {
                    log.warn("Error consultando orden #{} en nivel {} — reseteando nivel: {}",
                        level.getActiveOrder().getOrderId(), level.getIndex(), e.getMessage());
                    level.setActiveOrder(null);
                    level.setStatus(GridLevel.Status.IDLE);
                    continue;
                }

                if (order.isFilled()) {
                    handleFill(level, order);
                } else if (order.getStatus() == GridOrder.Status.CANCELED
                        || order.getStatus() == GridOrder.Status.REJECTED
                        || order.getStatus() == GridOrder.Status.EXPIRED) {
                    log.warn("Orden #{} en nivel {} fue {} — reseteando nivel",
                        order.getOrderId(), level.getIndex(), order.getStatus());
                    level.setActiveOrder(null);
                    level.setStatus(GridLevel.Status.IDLE);
                }
            }

            checkRiskLimits();
            saveState();

        } catch (Exception e) {
            log.error("Error verificando órdenes: {}", e.getMessage(), e);
        }
    }

    private void handleFill(GridLevel level, GridOrder filledOrder) {
        if (filledOrder.getSide() == GridOrder.Side.BUY) {
            log.info("[FILL] COMPRA nivel {} | Precio: {:.4f} | Qty: {:.6f}",
                level.getIndex(), filledOrder.getPrice(), filledOrder.getExecutedQty());

            tracker.recordBuy(filledOrder);
            level.setLastBuyOrder(filledOrder);
            level.setStatus(GridLevel.Status.BOUGHT);
            level.setActiveOrder(null);

            // Colocar venta en el nivel superior
            int sellLevelIdx = level.getIndex() + 1;
            if (sellLevelIdx < levels.size()) {
                GridLevel sellLevel = levels.get(sellLevelIdx);
                if (sellLevel.getStatus() == GridLevel.Status.IDLE) {
                    placeSellOrder(sellLevel, filledOrder.getExecutedQty(), filledOrder);
                }
            }

        } else { // SELL
            log.info("[FILL] VENTA nivel {} | Precio: {:.4f} | Qty: {:.6f}",
                level.getIndex(), filledOrder.getPrice(), filledOrder.getExecutedQty());

            // Buscar la compra emparejada (nivel inferior)
            int buyLevelIdx = level.getIndex() - 1;
            GridOrder pairedBuy = null;
            if (buyLevelIdx >= 0) {
                pairedBuy = levels.get(buyLevelIdx).getLastBuyOrder();
            }

            if (pairedBuy != null) {
                tracker.recordSell(filledOrder, pairedBuy);
            } else {
                tracker.recordSell(filledOrder);
            }

            level.setStatus(GridLevel.Status.SOLD);
            level.setActiveOrder(null);
            level.incrementCycles();

            // Verificar y ejecutar compounding antes de recolocar la compra
            if (compounding.shouldCompound(tracker.getCompletedCycles(), tracker.getRealizedPnl())) {
                applyCompounding();
            }

            // Recolocar compra en el nivel inferior (solo si la tendencia lo permite)
            if (buyLevelIdx >= 0) {
                GridLevel buyLevel = levels.get(buyLevelIdx);
                if (buyLevel.getStatus() == GridLevel.Status.BOUGHT
                        || buyLevel.getStatus() == GridLevel.Status.SOLD
                        || buyLevel.getStatus() == GridLevel.Status.IDLE) {
                    buyLevel.setStatus(GridLevel.Status.IDLE);
                    if (trendFilter.allowBuy()) {
                        placeBuyOrder(buyLevel);
                    } else {
                        log.info("[TrendFilter] BAJISTA — compra suspendida en nivel {}", buyLevel.getIndex());
                    }
                }
            }
        }
    }

    // -------------------------------------------------------
    //  REBALANCEO DINÁMICO
    // -------------------------------------------------------

    private synchronized void checkRangeAndRebalance() {
        try {
            currentPrice = client.getCurrentPrice(config.getSymbol());

            // Refrescar tendencia y reactivar compras si la tendencia mejoró
            TrendFilter.Trend prev  = trendFilter.getTrend();
            TrendFilter.Trend trend = trendFilter.refresh(currentPrice);

            if (prev == TrendFilter.Trend.BEARISH && trend != TrendFilter.Trend.BEARISH) {
                log.info("[TrendFilter] Tendencia cambió a {} — reactivando compras suspendidas", trend.label());
                resumeSuspendedBuys();
            }

            // Refrescar filtro de entrada RSI+BB
            EntryFilter.Signal signal = entryFilter.evaluate(currentPrice);

            boolean needsRebalance = rangeManager.needsRebalance(currentPrice);

            if (needsRebalance || pendingRebalance) {
                if (entryFilter.isEnabled() && signal == EntryFilter.Signal.UNFAVORABLE) {
                    pendingRebalance = true;
                    log.info("[EntryFilter] Rebalanceo diferido — señal DESFAVORABLE (RSI={:.1f}, BB%B={:.3f})",
                        entryFilter.getRsi(), entryFilter.getBbPercent());
                } else {
                    if (pendingRebalance) {
                        log.info("[EntryFilter] Señal mejoró a {} — ejecutando rebalanceo diferido", signal);
                    } else {
                        log.info("Iniciando rebalanceo del grid | Precio: {:.4f}", currentPrice);
                    }
                    pendingRebalance = false;
                    rebalanceGrid();
                }
            }
        } catch (Exception e) {
            log.error("Error verificando rango: {}", e.getMessage(), e);
        }
    }

    /** Reactiva órdenes de compra en niveles que quedaron IDLE por el filtro de tendencia. */
    private void resumeSuspendedBuys() {
        int resumed = 0;
        for (GridLevel level : levels) {
            if (level.getStatus() == GridLevel.Status.IDLE
                    && level.getPrice() < currentPrice
                    && resumed < config.getMaxActiveOrders()) {
                placeBuyOrder(level);
                resumed++;
            }
        }
        if (resumed > 0) {
            log.info("[TrendFilter] {} compras reactivadas tras cambio de tendencia", resumed);
        }
    }

    private void rebalanceGrid() {
        // 1. Cancelar todas las órdenes activas
        if (!config.isDryRun()) {
            client.cancelAllOrders(config.getSymbol());
        }
        for (GridLevel level : levels) {
            level.setActiveOrder(null);
            level.setStatus(GridLevel.Status.IDLE);
        }

        // 2. Recalcular rango
        double[] range = rangeManager.calculateRange(currentPrice);
        double[] newPrices = computePriceLevels(range[0], range[1]);

        // 3. Rehacer niveles
        levels = new ArrayList<>();
        for (int i = 0; i < newPrices.length; i++) {
            double qty = computeQuantity(newPrices[i]);
            levels.add(new GridLevel(i, newPrices[i], qty));
        }

        // 4. Colocar órdenes de compra de nuevo
        int placed = 0;
        for (GridLevel level : levels) {
            if (level.getPrice() < currentPrice && placed < config.getMaxActiveOrders()) {
                placeBuyOrder(level);
                placed++;
            }
        }

        log.info("Rebalanceo completo. Nuevo rango: [{:.4f} - {:.4f}] | {} órdenes colocadas",
            range[0], range[1], placed);
        saveState();
    }

    // -------------------------------------------------------
    //  COLOCACIÓN DE ÓRDENES
    // -------------------------------------------------------

    private void placeBuyOrder(GridLevel level) {
        String priceStr = NumberUtil.formatPrice(level.getPrice(), exchangeInfo.getTickSize());
        String qtyStr   = NumberUtil.formatQty(level.getQuantity(), exchangeInfo.getStepSize());

        double price = Double.parseDouble(priceStr);
        double qty   = Double.parseDouble(qtyStr);

        if (!exchangeInfo.isValidOrder(price, qty)) {
            log.warn("Nivel {} inválido (notional bajo): price={} qty={}", level.getIndex(), priceStr, qtyStr);
            return;
        }

        GridOrder order;
        if (config.isDryRun()) {
            order = dryRunOrder(level.getIndex(), GridOrder.Side.BUY, price, qty);
        } else {
            order = client.placeLimitOrder(config.getSymbol(), GridOrder.Side.BUY, qty, price, priceStr, qtyStr);
        }

        order.setLevelIndex(level.getIndex());
        level.setActiveOrder(order);
        level.setStatus(GridLevel.Status.BUY_PLACED);

        log.debug("COMPRA colocada nivel {}: {} @ {}", level.getIndex(), qtyStr, priceStr);
    }

    private void placeSellOrder(GridLevel level, double qty, GridOrder pairedBuy) {
        String priceStr = NumberUtil.formatPrice(level.getPrice(), exchangeInfo.getTickSize());
        String qtyStr   = NumberUtil.formatQty(qty, exchangeInfo.getStepSize());

        double price = Double.parseDouble(priceStr);
        double parsedQty = Double.parseDouble(qtyStr);

        if (!exchangeInfo.isValidOrder(price, parsedQty)) {
            log.warn("Nivel venta {} inválido: price={} qty={}", level.getIndex(), priceStr, qtyStr);
            return;
        }

        GridOrder order;
        if (config.isDryRun()) {
            order = dryRunOrder(level.getIndex(), GridOrder.Side.SELL, price, parsedQty);
        } else {
            order = client.placeLimitOrder(config.getSymbol(), GridOrder.Side.SELL, parsedQty, price, priceStr, qtyStr);
        }

        order.setLevelIndex(level.getIndex());
        level.setActiveOrder(order);
        level.setQuantity(parsedQty);
        level.setStatus(GridLevel.Status.SELL_PLACED);

        double profit = (price - pairedBuy.getPrice()) * parsedQty;
        log.info("VENTA colocada nivel {}: {} @ {} | Ganancia esperada: {:.4f}",
            level.getIndex(), qtyStr, priceStr, profit);
    }

    // -------------------------------------------------------
    //  GESTIÓN DE RIESGO
    // -------------------------------------------------------

    private void checkRiskLimits() {
        if (tracker.isStopLossTriggered(config.getStopLossPercent())) {
            log.warn("STOP LOSS ACTIVADO! PnL: {:.4f} USDT ({:.2f}%)",
                tracker.getTotalPnl(), tracker.getPnlPercent());
            shutdown();
        } else if (tracker.isTakeProfitTriggered(config.getTakeProfitPercent())) {
            log.info("TAKE PROFIT ALCANZADO! PnL: {:.4f} USDT ({:.2f}%)",
                tracker.getTotalPnl(), tracker.getPnlPercent());
            shutdown();
        }
    }

    // -------------------------------------------------------
    //  UTILITARIOS
    // -------------------------------------------------------

    private double[] computePriceLevels(double lower, double upper) {
        int n = config.getGridLevels() + 1; // N+1 puntos forman N intervalos
        double[] prices = new double[n];

        if ("ARITHMETIC".equals(config.getSpacingType())) {
            double step = (upper - lower) / config.getGridLevels();
            for (int i = 0; i < n; i++) prices[i] = lower + i * step;
        } else { // GEOMETRIC
            double ratio = Math.pow(upper / lower, 1.0 / config.getGridLevels());
            for (int i = 0; i < n; i++) prices[i] = lower * Math.pow(ratio, i);
        }
        return prices;
    }

    /**
     * Reinvierte las ganancias realizadas aumentando el capital efectivo y
     * actualizando las cantidades en TODOS los niveles para que las próximas
     * órdenes de compra operen con el presupuesto compoundeado.
     * Los órdenes ya colocados no se modifican.
     */
    private void applyCompounding() {
        compounding.executeCompound(tracker.getRealizedPnl(), tracker.getCompletedCycles());

        int updated = 0;
        for (GridLevel level : levels) {
            double newQty = compounding.computeQuantity(level.getPrice());
            level.setQuantity(newQty);
            updated++;
        }

        log.info("[Compounding] Cantidades actualizadas en {} niveles | Capital efectivo: {:.2f} USDT",
            updated, compounding.getEffectiveCapital());
        saveState();
    }

    private double computeQuantity(double price) {
        return compounding.computeQuantity(price);
    }

    private void syncWithOpenOrders() {
        try {
            List<GridOrder> openOrders = client.getOpenOrders(config.getSymbol());
            log.info("Sincronizando con {} órdenes abiertas en Binance", openOrders.size());
            for (GridOrder order : openOrders) {
                for (GridLevel level : levels) {
                    if (Math.abs(level.getPrice() - order.getPrice()) < 0.01) {
                        level.setActiveOrder(order);
                        level.setStatus(order.getSide() == GridOrder.Side.BUY
                            ? GridLevel.Status.BUY_PLACED : GridLevel.Status.SELL_PLACED);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error sincronizando órdenes: {}", e.getMessage());
        }
    }

    private long dryRunOrderId = 1_000_000;

    private GridOrder dryRunOrder(int levelIdx, GridOrder.Side side, double price, double qty) {
        GridOrder order = new GridOrder(++dryRunOrderId, config.getSymbol(), side, price, qty);
        order.setStatus(GridOrder.Status.NEW);
        order.setLevelIndex(levelIdx);
        log.debug("[DRY-RUN] {} #{}: {} @ {:.4f} x {:.6f}",
            side, dryRunOrderId, config.getSymbol(), price, qty);
        return order;
    }

    // Simula llenado de órdenes en dry-run cuando el precio cruza el nivel
    private GridOrder simulateOrderCheck(GridLevel level) {
        GridOrder order = level.getActiveOrder();
        if (order == null) return null;

        boolean filled = (order.getSide() == GridOrder.Side.BUY  && currentPrice <= order.getPrice())
                      || (order.getSide() == GridOrder.Side.SELL && currentPrice >= order.getPrice());

        if (filled) {
            order.setStatus(GridOrder.Status.FILLED);
            order.setExecutedQty(order.getOrigQty());
        }
        return order;
    }

    private void saveState() {
        GridState state = new GridState();
        state.setSymbol(config.getSymbol());
        state.setInitialPrice(currentPrice);
        state.setLowerBound(rangeManager.getLowerBound());
        state.setUpperBound(rangeManager.getUpperBound());
        state.setLastAtr(rangeManager.getLastAtr());
        state.setTotalInvested(tracker.getTotalBuyValue());
        state.setRealizedPnl(tracker.getRealizedPnl());
        state.setTotalCycles(tracker.getCompletedCycles());
        state.setLevels(new ArrayList<>(levels));
        stateManager.save(state);
    }

    private void refreshDisplay() {
        monitor.render(levels, currentPrice);
    }

    private void awaitShutdown() {
        try {
            while (running) Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Getters para monitor
    public List<GridLevel> getLevels()          { return levels; }
    public double getCurrentPrice()             { return currentPrice; }
    public PositionTracker getTracker()         { return tracker; }
    public DynamicRangeManager getRangeManager(){ return rangeManager; }
}
