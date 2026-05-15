# Binance Grid Trader

Bot de Grid Trading para Binance con rango dinámico (ATR), filtro de tendencia, compounding automático y filtro de entrada RSI + Bollinger Bands.

---

## Tabla de Contenidos

1. [Cómo funciona](#cómo-funciona)
2. [Prerequisitos](#prerequisitos)
3. [Instalación](#instalación)
4. [Configuración de credenciales API](#configuración-de-credenciales-api)
5. [Configuración del grid](#configuración-del-grid)
6. [Filtro de Tendencia](#filtro-de-tendencia)
7. [Compounding Automático](#compounding-automático)
8. [Filtro de Entrada RSI + Bollinger Bands](#filtro-de-entrada-rsi--bollinger-bands)
9. [Modos de operación](#modos-de-operación)
10. [Compilación y ejecución](#compilación-y-ejecución)
11. [Monitor en consola](#monitor-en-consola)
12. [Parámetros de referencia](#parámetros-de-referencia)
13. [Gestión de riesgo](#gestión-de-riesgo)
14. [Persistencia y recuperación](#persistencia-y-recuperación)
15. [Preguntas frecuentes](#preguntas-frecuentes)
16. [Advertencias de riesgo](#advertencias-de-riesgo)
17. [Estructura del proyecto](#estructura-del-proyecto)

---

## Cómo funciona

### Grid Trading básico

El bot divide un rango de precios en **N niveles equiespaciados**:

```
Nivel 5  [100$] <- precio actual
Nivel 4  [ 99$] <- orden COMPRA
Nivel 3  [ 98$] <- orden COMPRA
Nivel 2  [ 97$] <- orden COMPRA
Nivel 1  [ 96$] <- orden COMPRA
```

Cuando la **compra del nivel 3** se llena (precio baja a 98$), el bot coloca una **venta en el nivel 4** (99$). Al ejecutarse esa venta, la ganancia es: **$1 x cantidad**. El ciclo se repite indefinidamente mientras el precio oscile dentro del rango.

### Gestión de rango dinámica (ATR)

El ATR (Average True Range) mide la volatilidad real de las últimas N velas. El bot:

1. Calcula el ATR para el símbolo configurado (ej. BTCUSDT, intervalo 1h, período 14)
2. Define el rango como: `precio +/- (ATR x multiplicador)`
3. Ajusta la cuadrícula automáticamente cuando el precio se acerca a un borde

**Resultado**: en mercados volátiles el grid es más amplio (menos rebalanceos), en mercados tranquilos es más estrecho (más ciclos por día).

### Filtro de Tendencia (MA)

Dos medias móviles simples protegen el capital en tendencias bajistas fuertes:

- `precio > MA_rapida > MA_lenta` → **ALCISTA** — grid opera con normalidad
- `precio < MA_rapida < MA_lenta` → **BAJISTA** — nuevas compras **suspendidas**
- Cualquier otra combinación → **LATERAL** — grid opera con normalidad

Cuando la tendencia mejora de BAJISTA a otro estado, el bot **reactiva automáticamente** todas las compras suspendidas sin necesidad de reiniciar.

### Compounding Automático

Tras alcanzar un umbral configurable (por ciclos o por monto de PnL), el bot reinvierte las ganancias realizadas aumentando el capital efectivo del grid. Las siguientes órdenes de compra se colocan con mayor presupuesto, amplificando los retornos futuros de forma compuesta.

### Filtro de Entrada RSI + Bollinger Bands

Evalúa el momentum del mercado antes de ejecutar rebalanceos, asegurando que el grid no se reinicie en zonas de sobrecompra:

- **RSI < umbral_sobreventa** → precio en zona de valor (favorable para comprar)
- **BB%B < 0.3** → precio en el tercio inferior de las bandas (zona de valor)
- **RSI > umbral_sobrecompra Y BB%B > 0.85** → mercado extendido, rebalanceo diferido
- Cuando la señal mejora, el rebalanceo pendiente se ejecuta automáticamente

---

## Prerequisitos

| Requisito      | Versión mínima |
|----------------|---------------|
| Java JDK       | 17+           |
| Maven          | 3.8+          |
| Cuenta Binance | Verificada    |

```bash
java --version   # debe mostrar 17+
mvn --version    # debe mostrar 3.8+
```

---

## Instalación

```bash
cd ~/Projects/Java/binance-bots/binance-grid-trader
mvn clean package -q
# JAR ejecutable en: target/grid-trader.jar
```

---

## Configuración de credenciales API

### Testnet (recomendado para empezar)

1. Ve a **https://testnet.binance.vision/**
2. Login con GitHub → **"Generate HMAC_SHA256 Key"**
3. Copia la **API Key** y el **Secret Key** (el secret solo se muestra una vez)

### Mercado real (Binance.com)

1. Ve a **https://www.binance.com/es/my/settings/api-management**
2. Crea una clave API con permiso: **"Enable Spot & Margin Trading"**
3. Restringe a tu IP (más seguro)
4. **Nunca actives permisos de retiro**

### Editar config.properties

```properties
binance.api.key=TU_KEY_AQUI
binance.api.secret=TU_SECRET_AQUI
binance.testnet=true   # false para mercado real
```

> **Seguridad**: `config.properties` está en `.gitignore`. Nunca lo subas a ningún repositorio.

---

## Configuración del grid

### Parámetros básicos

```properties
grid.symbol=BTCUSDT          # par de trading
grid.levels=10               # niveles en la cuadrícula (recomendado: 5-20)
grid.investment.total=1000   # capital total en USDT
grid.spacing.type=GEOMETRIC  # GEOMETRIC o ARITHMETIC
grid.spacing.percent=1.0     # % entre niveles (solo si dynamic.range=false)
```

### Espaciado geométrico vs aritmético

| Tipo         | Descripción                        | Ideal para                        |
|--------------|------------------------------------|-----------------------------------|
| `GEOMETRIC`  | % constante entre niveles          | BTC, ETH (movimientos % relativos)|
| `ARITHMETIC` | diferencia fija de $ entre niveles | rangos estrechos, baja volatilidad|

### Rango dinámico (ATR)

```properties
grid.dynamic.range.enabled=true
grid.atr.period=14        # velas para calcular el ATR
grid.atr.interval=1h      # intervalo: 1m 5m 15m 30m 1h 4h 1d
grid.atr.multiplier=2.0   # rango = precio +/- (ATR x multiplicador)
grid.rebalance.threshold=0.7  # rebalancear al llegar al 70% del borde
```

**Guía de multiplicador ATR:**

| Mercado          | Multiplicador |
|------------------|--------------|
| Tranquilo        | 1.5          |
| Normal           | 2.0          |
| Alta volatilidad | 3.0          |

---

## Filtro de Tendencia

Protege el capital suspendiendo compras cuando el mercado tiene una tendencia bajista clara.

```properties
grid.trend.filter.enabled=true

# MA rapida: senal de corto plazo
grid.trend.filter.fast.period=50

# MA lenta: tendencia de fondo
grid.trend.filter.slow.period=200

# Intervalo de velas para las MAs (igual o mayor que atr.interval)
grid.trend.filter.interval=1h

# Cada cuantos segundos recalcular (300 = 5 min)
grid.trend.filter.check.interval=300
```

### Lógica de decisión

```
precio > MA50 > MA200  ->  ALCISTA   ->  compras activas
precio < MA50 < MA200  ->  BAJISTA   ->  compras PAUSADAS automaticamente
cualquier otra combo   ->  LATERAL   ->  compras activas
```

Cuando la tendencia cambia de BAJISTA a otro estado, **el bot reactiva las compras sin intervención manual**.

### Guía de períodos por intervalo

| Intervalo | MA rápida | MA lenta | Notas                         |
|-----------|-----------|----------|-------------------------------|
| `1h`      | 50        | 200      | Equilibrio señal/ruido        |
| `4h`      | 25        | 100      | Menos señales, más fiables    |
| `1d`      | 20        | 50       | Tendencias de largo plazo     |
| `15m`     | 50        | 200      | Más señales, más ruido        |

---

## Compounding Automático

Reinvierte las ganancias realizadas para que cada ciclo posterior opere con más capital, generando retornos de forma exponencial.

```properties
grid.compounding.enabled=true

# Modo de disparo:
# CYCLES     = compound cada N ciclos buy->sell completados
# PNL_AMOUNT = compound cada vez que el PnL acumulado supere X USDT
grid.compounding.trigger=CYCLES

# Umbral para trigger=CYCLES (numero de ciclos entre cada compound)
grid.compounding.cycles.threshold=5

# Umbral para trigger=PNL_AMOUNT (USDT de ganancia entre cada compound)
grid.compounding.pnl.threshold=10.0

# Que porcentaje del PnL nuevo se reinvierte
# 100 = todo se reinvierte | 50 = mitad se reinvierte, mitad queda libre
grid.compounding.reinvest.percent=100.0
```

### Ejemplo de crecimiento compuesto

Con $1,000 de inversión, 10 niveles, 1% de ganancia por ciclo y compound cada 5 ciclos reinvirtiendo el 100%:

| Evento          | Capital efectivo | Ganancia por ciclo |
|-----------------|------------------|--------------------|
| Inicio          | $1,000.00        | $1.00              |
| Compound #1     | $1,050.00        | $1.05              |
| Compound #2     | $1,102.50        | $1.10              |
| Compound #3     | $1,157.63        | $1.16              |
| Compound #10    | $1,628.89        | $1.63              |

### Comportamiento durante el compound

1. Se registra el evento y se calcula el PnL nuevo desde el último compound
2. Se suma al capital efectivo: `capital += PnL_nuevo x (reinvest_percent / 100)`
3. Se actualizan las cantidades en **todos** los niveles con el nuevo capital
4. Los órdenes activos en ese momento **no se modifican**
5. Las siguientes órdenes de compra usan automáticamente el nuevo presupuesto

---

## Filtro de Entrada RSI + Bollinger Bands

Evalúa el momentum antes de ejecutar rebalanceos del grid, evitando reiniciar la cuadrícula cuando el mercado está sobrecomprado.

```properties
grid.entry.filter.enabled=true

# Intervalo de velas para calcular RSI y BB
grid.entry.filter.interval=1h

# Periodo del RSI (numero de velas, estandar: 14)
grid.entry.filter.rsi.period=14

# RSI < oversold -> senal FAVORABLE (precio en zona de valor)
grid.entry.filter.rsi.oversold=35.0

# RSI > overbought -> contribuye a senal NO OPERAR
grid.entry.filter.rsi.overbought=65.0

# Periodo de la media movil de las Bandas de Bollinger
grid.entry.filter.bb.period=20

# Multiplicador de desviacion estandar (2.0 = bandas estandar)
grid.entry.filter.bb.std.dev=2.0

# BB%B < threshold -> senal FAVORABLE (precio en tercio inferior de las bandas)
grid.entry.filter.bb.threshold=0.3

# true  = FAVORABLE solo si RSI < oversold Y BB%B < threshold (mas restrictivo)
# false = FAVORABLE si RSI < oversold O BB%B < threshold (mas senales)
grid.entry.filter.require.both=false

# Cada cuantos segundos recalcular RSI y BB (300 = 5 min)
grid.entry.filter.check.interval=300
```

### Lógica de señales

| Señal        | Condición                                    | Efecto                              |
|--------------|----------------------------------------------|-------------------------------------|
| FAVORABLE    | RSI < 35 O BB%B < 0.3 (o ambos si require.both=true) | Rebalanceo ejecutado normalmente |
| NEUTRO       | Condiciones intermedias                      | Rebalanceo ejecutado normalmente    |
| NO OPERAR    | RSI > 65 Y BB%B > 0.85                       | Rebalanceo **diferido** hasta que mejore |

### Comportamiento del rebalanceo diferido

Cuando un rebalanceo queda pendiente por señal NO OPERAR:
1. Se activa la bandera `pendingRebalance`
2. En cada ciclo de `range.check.interval` se reevalúa la señal
3. En cuanto la señal mejora a FAVORABLE o NEUTRO, el rebalanceo se ejecuta automáticamente

### Guía de parámetros RSI

| Umbral sobreventa | Efecto                                              |
|-------------------|-----------------------------------------------------|
| 30                | Muy restrictivo — solo señal en caídas extremas     |
| 35                | Recomendado — buen equilibrio señal/frecuencia      |
| 40                | Más señales — menor filtrado                        |

---

## Modos de operación

### 1. Dry-Run — recomendado para empezar

```properties
grid.dry.run=true
```

Las órdenes se simulan internamente: cuando el precio cruza un nivel, el bot registra el fill sin enviar nada a Binance. Ideal para validar la configuración.

### 2. Testnet

```properties
grid.dry.run=false
binance.testnet=true
```

Órdenes reales sobre el testnet de Binance con fondos virtuales.

### 3. Producción

```properties
grid.dry.run=false
binance.testnet=false
```

**Opera con dinero real.** Solo usar tras validar en testnet.

---

## Compilación y ejecución

```bash
# Compilar
mvn clean package -q

# Ejecutar (usa config.properties del directorio actual)
java -jar target/grid-trader.jar

# Con archivo de configuración personalizado
java -jar target/grid-trader.jar /ruta/mi-config.properties

# Con más memoria (recomendado para ejecuciones largas)
java -Xmx256m -jar target/grid-trader.jar

# En segundo plano
nohup java -Xmx256m -jar target/grid-trader.jar > /dev/null 2>&1 &
echo "PID: $!"
```

### Script de inicio

```bash
#!/bin/bash
# start.sh
cd "$(dirname "$0")"
mkdir -p logs
java -Xmx256m -jar target/grid-trader.jar config.properties
```

```bash
chmod +x start.sh
./start.sh
```

### Detener el bot

```bash
# Ctrl+C en la terminal — cancela todas las ordenes automaticamente

# Si corre en background:
kill -SIGTERM <PID>
```

> El shutdown hook siempre cancela las órdenes abiertas antes de salir.

---

## Monitor en consola

El dashboard se actualiza en tiempo real con todas las métricas del bot:

```
╔════════════════════════════════════════════════════════════════╗
║  BTCUSDT             BINANCE GRID TRADER             14:23:01  ║
╠════════════════════════════════════════════════════════════════╣
║  Precio actual:     62450.25 USDT   Modo: TESTNET              ║
║  Rango:  [     60200.00  --      64700.00 ]  ATR:   1125.50    ║
║  Niveles: 10   Espaciado: GEOMETRIC     Dinamico: Si           ║
║  Tendencia: ** ALCISTA **  MA50:  63100.00  MA200: 61200.00    ║
║  Compras: activas                                              ║
║  RSI(14):  32.5 sobrevendido     BB%B: 0.187                   ║
║  Entrada: ** FAVORABLE  **                                     ║
╠════════════════════════════════════════════════════════════════╣
║  PnL Realizado:    +45.2000 USDT    Ciclos:  15                ║
║  PnL No Real.:      +5.2100 USDT    ROI:     +4.52%            ║
║  Capital usado:    500.00 USDT      Tiempo:  02:15:33          ║
║  Efectivo:       1045.20 USDT (+4.52%)  Compounds: 3           ║
║  Proximo compound: 2/5 ciclos                                  ║
╠════════════════════════════════════════════════════════════════╣
║  #      Precio          Estado           Orden                 ║
║────────────────────────────────────────────────────────────────║
║  [10]      64700.0000  - sin orden                             ║
║  [ 9]      63720.5000  ^ vendiendo      #10023      ►          ║
║  [ 8]      62750.9000  * comprado                   ►          ║
║  [ 7]      61790.3000  v esperando      #10019                 ║
║  [ 6]      60839.6000  v esperando      #10015                 ║
╚════════════════════════════════════════════════════════════════╝
  [Ctrl+C] Detener bot y cancelar ordenes
```

### Secciones del dashboard

| Sección          | Contenido                                                              |
|------------------|------------------------------------------------------------------------|
| **Mercado**      | Precio actual, rango ATR, configuración del grid                       |
| **Tendencia**    | Estado MA (ALCISTA/LATERAL/BAJISTA), valores MA rápida y lenta, estado compras |
| **RSI + BB**     | Valor RSI con etiqueta, BB%B, señal de entrada (FAVORABLE/NEUTRO/NO OPERAR) |
| **P&L**          | Ganancias realizadas y no realizadas, ROI, capital invertido, tiempo   |
| **Compounding**  | Capital efectivo actual, número de compounds, próximo compound         |
| **Niveles**      | Estado de cada nivel de precio con ID de orden y marcador de precio    |

### Estados de los niveles

| Símbolo       | Estado                                      |
|---------------|---------------------------------------------|
| `- sin orden` | Nivel sin actividad                         |
| `v esperando` | Orden de compra activa                      |
| `* comprado`  | Compra ejecutada, venta pendiente de colocar|
| `^ vendiendo` | Orden de venta activa                       |
| `* vendido`   | Ciclo completado                            |
| `►`           | Precio actual cerca de este nivel           |

---

## Parámetros de referencia

### Referencia completa de parámetros

| Parámetro | Por defecto | Descripción |
|-----------|-------------|-------------|
| `binance.testnet` | `true` | Usar testnet de Binance |
| `grid.symbol` | — | Par de trading (ej. `BTCUSDT`) |
| `grid.levels` | — | Número de niveles (2–100) |
| `grid.investment.total` | — | Capital total en USDT |
| `grid.spacing.type` | `GEOMETRIC` | `GEOMETRIC` o `ARITHMETIC` |
| `grid.spacing.percent` | `1.0` | % de espaciado (cuando dynamic=false) |
| `grid.dynamic.range.enabled` | `true` | Rango adaptativo por ATR |
| `grid.atr.period` | `14` | Período del ATR en velas |
| `grid.atr.interval` | `1h` | Intervalo de velas para ATR |
| `grid.atr.multiplier` | `2.0` | Amplitud = ATR × multiplicador |
| `grid.rebalance.threshold` | `0.7` | Umbral de rebalanceo (0–1) |
| `grid.stop.loss.percent` | `5.0` | Stop loss global (0 = desactivado) |
| `grid.take.profit.percent` | `20.0` | Take profit global (0 = desactivado) |
| `grid.max.active.orders` | `20` | Máximo de órdenes abiertas simultáneas |
| `grid.order.check.interval` | `15` | Segundos entre revisiones de órdenes |
| `grid.monitor.interval` | `10` | Segundos entre actualizaciones del display |
| `grid.range.check.interval` | `300` | Segundos entre recálculos de ATR y tendencia |
| `grid.api.rate.limit.delay` | `200` | Ms entre peticiones API (mín. 100) |
| `grid.state.file` | `grid-state.json` | Archivo de persistencia de estado |
| `grid.trend.filter.enabled` | `false` | Activar filtro de tendencia MA |
| `grid.trend.filter.fast.period` | `50` | Período MA rápida |
| `grid.trend.filter.slow.period` | `200` | Período MA lenta |
| `grid.trend.filter.interval` | `1h` | Intervalo de velas para MAs |
| `grid.trend.filter.check.interval` | `300` | Segundos entre recálculos de tendencia |
| `grid.compounding.enabled` | `false` | Activar compounding automático |
| `grid.compounding.trigger` | `CYCLES` | `CYCLES` o `PNL_AMOUNT` |
| `grid.compounding.cycles.threshold` | `5` | Ciclos entre cada compound |
| `grid.compounding.pnl.threshold` | `10.0` | USDT de PnL entre cada compound |
| `grid.compounding.reinvest.percent` | `100.0` | % del PnL nuevo a reinvertir |
| `grid.entry.filter.enabled` | `false` | Activar filtro RSI + Bollinger Bands |
| `grid.entry.filter.interval` | `1h` | Intervalo de velas para RSI y BB |
| `grid.entry.filter.rsi.period` | `14` | Período del RSI en velas |
| `grid.entry.filter.rsi.oversold` | `35.0` | Umbral de sobreventa RSI (señal FAVORABLE) |
| `grid.entry.filter.rsi.overbought` | `65.0` | Umbral de sobrecompra RSI (señal NO OPERAR) |
| `grid.entry.filter.bb.period` | `20` | Período de la media móvil de las BB |
| `grid.entry.filter.bb.std.dev` | `2.0` | Multiplicador de desviación estándar |
| `grid.entry.filter.bb.threshold` | `0.3` | BB%B máximo para señal FAVORABLE |
| `grid.entry.filter.require.both` | `false` | Requerir RSI Y BB (true) o cualquiera (false) |
| `grid.entry.filter.check.interval` | `300` | Segundos entre recálculos de RSI y BB |
| `grid.dry.run` | `true` | Paper trading sin órdenes reales |

---

## Gestión de riesgo

```properties
# Detiene el bot si la perdida total supera X% del capital inicial
grid.stop.loss.percent=5.0

# Detiene el bot al alcanzar X% de ganancia total
grid.take.profit.percent=20.0
```

Al activarse Stop Loss o Take Profit el bot:
1. Cancela todas las órdenes abiertas en Binance
2. Guarda el estado final en `grid-state.json`
3. Termina el proceso limpiamente

**Recomendaciones de riesgo:**
- Empieza siempre con `grid.dry.run=true` para validar la configuración
- Activa el filtro de tendencia en mercados con alta volatilidad direccional
- Usa `reinvest.percent < 100` si quieres retirar ganancias periódicamente
- No configures compounding con `cycles.threshold=1` en grids muy activos
- Activa el filtro RSI+BB para evitar rebalanceos en zonas de sobrecompra

---

## Persistencia y recuperación

El bot guarda el estado en `grid-state.json` tras cada cambio relevante:

```bash
# Reiniciar (carga el estado previo automaticamente)
java -jar target/grid-trader.jar

# Ver el estado guardado
cat grid-state.json | python3 -m json.tool

# Inicio limpio (ignora el estado previo)
rm grid-state.json && java -jar target/grid-trader.jar
```

El estado incluye: niveles y sus órdenes activas, PnL acumulado, capital efectivo del compounding, rango actual del grid y parámetros de tendencia.

---

## Logs

```bash
# Log en tiempo real
tail -f logs/grid-trader.log

# Filtrar fills de ordenes
grep "FILL" logs/grid-trader.log

# Ver eventos de compounding
grep "COMPOUNDING" logs/grid-trader.log

# Ver cambios de tendencia
grep "TrendFilter" logs/grid-trader.log

# Ver senales del filtro de entrada RSI+BB
grep "EntryFilter" logs/grid-trader.log

# Ver rebalanceos del grid
grep "rebalanceo\|Rebalanceo" logs/grid-trader.log
```

---

## Preguntas frecuentes

**¿Con cuánto capital mínimo puedo operar?**
Binance requiere un notional mínimo de 5 USDT por orden. Con 10 niveles necesitas al menos 50 USDT. Se recomienda un mínimo de 200 USDT para que los fills generen órdenes válidas tras el compounding.

**¿Activo el filtro de tendencia desde el primer día?**
Sí. Es especialmente útil al empezar porque protege si el mercado está en una caída cuando arrancas el bot. El coste es que en tendencias alcistas fuertes puede perder algunos ciclos al rebotar desde abajo.

**¿Con qué trigger de compounding empezar?**
`CYCLES` con `threshold=5` es el más predecible. `PNL_AMOUNT` puede tardar mucho en dispararse en grids lentos o hacerlo muy frecuentemente en grids activos.

**¿Qué pasa si reinicio el bot con compounding activo?**
El estado guardado en `grid-state.json` incluye el capital efectivo compoundeado. Al reiniciar, el bot carga ese capital y continúa desde donde estaba.

**¿ARITHMETIC o GEOMETRIC?**
Usa `GEOMETRIC` para BTC/ETH (los movimientos son porcentuales). `ARITHMETIC` es mejor para rangos muy estrechos o pares de baja volatilidad.

**¿El filtro RSI+BB bloquea las compras individuales?**
No, solo difiere los **rebalanceos** completos del grid. Las compras en niveles individuales solo están controladas por el filtro de tendencia.

**¿El bot opera con futuros o margin?**
Solo Spot. No tiene soporte para futuros perpetuos ni trading con apalancamiento.

---

## Advertencias de riesgo

> **AVISO**: Este software es para fines educativos. El trading de criptomonedas conlleva riesgo de pérdida total del capital invertido.

- Prueba siempre en **testnet** o **dry-run** antes de usar dinero real
- El Grid Trading genera pérdidas en tendencias fuertes fuera del rango — el filtro de tendencia reduce pero **no elimina** este riesgo
- El compounding amplifica tanto las ganancias como las pérdidas potenciales
- **Nunca actives permisos de retiro** en las claves API del bot
- Monitorea el bot regularmente, especialmente en períodos de alta volatilidad

---

## Estructura del proyecto

```
binance-grid-trader/
├── config.properties               <- Configuracion principal (editar aqui)
├── pom.xml
├── README.md
├── grid-state.json                 <- Estado del grid (generado en ejecucion)
├── logs/
│   └── grid-trader.log
└── src/main/java/com/gridtrader/
    ├── Main.java
    ├── config/
    │   └── AppConfig.java          <- Carga y valida todos los parametros
    ├── api/
    │   └── BinanceRestClient.java  <- Cliente REST con firma HMAC-SHA256
    ├── model/
    │   ├── GridOrder.java
    │   ├── GridLevel.java
    │   ├── GridState.java
    │   ├── ExchangeInfo.java
    │   └── Kline.java
    ├── strategy/
    │   ├── GridStrategy.java           <- Motor principal del grid
    │   ├── DynamicRangeManager.java    <- Calculo ATR y rebalanceo
    │   ├── TrendFilter.java            <- Filtro MA (ALCISTA/LATERAL/BAJISTA)
    │   └── EntryFilter.java            <- Filtro RSI + Bollinger Bands
    ├── service/
    │   ├── PositionTracker.java        <- Seguimiento de PnL (realizado y no realizado)
    │   ├── CompoundingManager.java     <- Reinversion automatica de ganancias
    │   └── StateManager.java          <- Persistencia del estado
    ├── monitor/
    │   └── ConsoleMonitor.java         <- Dashboard en terminal
    └── util/
        ├── HmacUtil.java
        └── NumberUtil.java
```
