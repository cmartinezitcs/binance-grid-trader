package com.gridtrader.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridtrader.config.AppConfig;
import com.gridtrader.model.ExchangeInfo;
import com.gridtrader.model.GridOrder;
import com.gridtrader.model.Kline;
import com.gridtrader.util.HmacUtil;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BinanceRestClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceRestClient.class);

    private static final String SPOT_BASE      = "https://api.binance.com";
    private static final String TESTNET_BASE   = "https://testnet.binance.vision";
    private static final MediaType JSON_MEDIA  = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final long rateLimitDelay;

    public BinanceRestClient(AppConfig config) {
        this.baseUrl        = config.isTestnet() ? TESTNET_BASE : SPOT_BASE;
        this.apiKey         = config.getApiKey();
        this.apiSecret      = config.getApiSecret();
        this.rateLimitDelay = config.getApiRateLimitDelay();
        this.mapper         = new ObjectMapper();
        this.http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    // -------------------------------------------------------
    //  ENDPOINTS PÚBLICOS
    // -------------------------------------------------------

    public double getCurrentPrice(String symbol) {
        String body = get("/api/v3/ticker/price?symbol=" + symbol, false);
        try {
            JsonNode node = mapper.readTree(body);
            return Double.parseDouble(node.get("price").asText());
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo precio de " + symbol, e);
        }
    }

    public List<Kline> getKlines(String symbol, String interval, int limit) {
        String url = "/api/v3/klines?symbol=" + symbol
            + "&interval=" + interval
            + "&limit=" + limit;
        String body = get(url, false);
        List<Kline> klines = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(body);
            for (JsonNode row : arr) {
                Kline k = new Kline(
                    row.get(0).asLong(),
                    Double.parseDouble(row.get(1).asText()),
                    Double.parseDouble(row.get(2).asText()),
                    Double.parseDouble(row.get(3).asText()),
                    Double.parseDouble(row.get(4).asText()),
                    Double.parseDouble(row.get(5).asText())
                );
                k.setCloseTime(row.get(6).asLong());
                klines.add(k);
            }
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo klines", e);
        }
        return klines;
    }

    public ExchangeInfo getExchangeInfo(String symbol) {
        String body = get("/api/v3/exchangeInfo?symbol=" + symbol, false);
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode symbolNode = root.get("symbols").get(0);

            ExchangeInfo info = new ExchangeInfo();
            info.setSymbol(symbolNode.get("symbol").asText());

            for (JsonNode filter : symbolNode.get("filters")) {
                String type = filter.get("filterType").asText();
                switch (type) {
                    case "PRICE_FILTER" -> {
                        info.setTickSize(filter.get("tickSize").asText());
                    }
                    case "LOT_SIZE" -> {
                        info.setStepSize(filter.get("stepSize").asText());
                        info.setMinQty(Double.parseDouble(filter.get("minQty").asText()));
                    }
                    case "NOTIONAL", "MIN_NOTIONAL" -> {
                        JsonNode mn = filter.has("minNotional") ? filter.get("minNotional") : filter.get("minNotionalValue");
                        if (mn != null) info.setMinNotional(Double.parseDouble(mn.asText()));
                    }
                }
            }
            return info;
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo exchange info para " + symbol, e);
        }
    }

    // -------------------------------------------------------
    //  ENDPOINTS PRIVADOS (requieren firma)
    // -------------------------------------------------------

    public JsonNode getAccount() {
        Map<String, String> params = new LinkedHashMap<>();
        addTimestamp(params);
        sign(params);
        String body = get("/api/v3/account?" + buildQueryString(params), true);
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo cuenta", e);
        }
    }

    public List<GridOrder> getOpenOrders(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        addTimestamp(params);
        sign(params);
        String body = get("/api/v3/openOrders?" + buildQueryString(params), true);
        return parseOrders(body);
    }

    public GridOrder getOrder(String symbol, long orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", String.valueOf(orderId));
        addTimestamp(params);
        sign(params);
        String body = get("/api/v3/order?" + buildQueryString(params), true);
        try {
            JsonNode node = mapper.readTree(body);
            // Binance devuelve {"code":-2013,"msg":"Order does not exist."} para órdenes inexistentes
            if (node.has("code")) {
                int code = node.get("code").asInt();
                String msg = node.path("msg").asText("Error desconocido");
                if (code == -2013) {
                    log.warn("Orden #{} no existe en Binance — marcando como CANCELADA", orderId);
                    GridOrder ghost = new GridOrder(orderId, symbol, null, 0, 0);
                    ghost.setStatus(GridOrder.Status.CANCELED);
                    return ghost;
                }
                throw new RuntimeException("Binance error " + code + ": " + msg);
            }
            return parseOrder(node);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error obteniendo orden " + orderId, e);
        }
    }

    public GridOrder placeLimitOrder(String symbol, GridOrder.Side side,
                                     double quantity, double price,
                                     String priceStr, String qtyStr) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side.name());
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTC");
        params.put("quantity", qtyStr);
        params.put("price", priceStr);
        addTimestamp(params);
        sign(params);

        log.info("[API] LIMIT {} {} @ {} qty={}", side, symbol, priceStr, qtyStr);

        String body = post("/api/v3/order", params);
        try {
            JsonNode node = mapper.readTree(body);
            if (node.has("code") && node.get("code").asInt() != 0) {
                throw new RuntimeException("Error de Binance: " + node.get("msg").asText()
                    + " (code=" + node.get("code").asInt() + ")");
            }
            return parseOrder(node);
        } catch (Exception e) {
            throw new RuntimeException("Error colocando orden LIMIT " + side + " @ " + priceStr, e);
        }
    }

    public void cancelOrder(String symbol, long orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", String.valueOf(orderId));
        addTimestamp(params);
        sign(params);

        log.info("[API] Cancelando orden #{}", orderId);
        delete("/api/v3/order", params);
    }

    public void cancelAllOrders(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        addTimestamp(params);
        sign(params);

        log.info("[API] Cancelando todas las órdenes de {}", symbol);
        delete("/api/v3/openOrders", params);
    }

    // -------------------------------------------------------
    //  HELPERS HTTP
    // -------------------------------------------------------

    private String get(String path, boolean withApiKey) {
        rateLimitSleep();
        Request.Builder builder = new Request.Builder().url(baseUrl + path).get();
        if (withApiKey) builder.header("X-MBX-APIKEY", apiKey);
        return execute(builder.build());
    }

    private String post(String path, Map<String, String> params) {
        rateLimitSleep();
        FormBody.Builder form = new FormBody.Builder();
        params.forEach(form::add);
        Request req = new Request.Builder()
            .url(baseUrl + path)
            .header("X-MBX-APIKEY", apiKey)
            .post(form.build())
            .build();
        return execute(req);
    }

    private String delete(String path, Map<String, String> params) {
        rateLimitSleep();
        Request req = new Request.Builder()
            .url(baseUrl + path + "?" + buildQueryString(params))
            .header("X-MBX-APIKEY", apiKey)
            .delete()
            .build();
        return execute(req);
    }

    private String execute(Request req) {
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful() && resp.code() != 400) {
                // 400 puede tener mensajes de error útiles
                log.error("HTTP {} de {}: {}", resp.code(), req.url(), body);
                throw new RuntimeException("HTTP " + resp.code() + ": " + body);
            }
            return body;
        } catch (IOException e) {
            throw new RuntimeException("Error de red en " + req.url(), e);
        }
    }

    // -------------------------------------------------------
    //  HELPERS DE FIRMA Y PARSEO
    // -------------------------------------------------------

    private void addTimestamp(Map<String, String> params) {
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
    }

    private void sign(Map<String, String> params) {
        String queryString = buildQueryString(params);
        params.put("signature", HmacUtil.sign(queryString, apiSecret));
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private GridOrder parseOrder(JsonNode node) {
        GridOrder order = new GridOrder();
        order.setOrderId(node.get("orderId").asLong());
        order.setSymbol(node.get("symbol").asText());
        order.setClientOrderId(node.path("clientOrderId").asText(""));
        order.setSide(GridOrder.Side.valueOf(node.get("side").asText()));
        order.setPrice(Double.parseDouble(node.get("price").asText()));
        order.setOrigQty(Double.parseDouble(node.get("origQty").asText()));
        order.setExecutedQty(Double.parseDouble(node.get("executedQty").asText()));
        order.setCreateTime(node.path("time").asLong(System.currentTimeMillis()));
        order.setUpdateTime(node.path("updateTime").asLong(0));

        String statusStr = node.get("status").asText("UNKNOWN");
        try {
            order.setStatus(GridOrder.Status.valueOf(statusStr));
        } catch (IllegalArgumentException e) {
            order.setStatus(GridOrder.Status.UNKNOWN);
        }
        return order;
    }

    private List<GridOrder> parseOrders(String body) {
        List<GridOrder> orders = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(body);
            for (JsonNode node : arr) {
                orders.add(parseOrder(node));
            }
        } catch (Exception e) {
            throw new RuntimeException("Error parseando órdenes", e);
        }
        return orders;
    }

    private void rateLimitSleep() {
        if (rateLimitDelay > 0) {
            try {
                Thread.sleep(rateLimitDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
