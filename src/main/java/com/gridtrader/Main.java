package com.gridtrader;

import com.gridtrader.config.AppConfig;
import com.gridtrader.strategy.GridStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.properties";

        log.info("Cargando configuración desde: {}", configPath);

        AppConfig config;
        try {
            config = AppConfig.load(configPath);
            log.info("Configuración: {}", config);
        } catch (Exception e) {
            log.error("Error cargando configuración: {}", e.getMessage());
            System.err.println("\nERROR: " + e.getMessage());
            System.err.println("Revisa config.properties antes de continuar.\n");
            System.exit(1);
            return;
        }

        GridStrategy strategy = new GridStrategy(config);

        // Hook de apagado limpio: cancela órdenes antes de terminar
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Señal de apagado recibida...");
            strategy.shutdown();
        }, "shutdown-hook"));

        try {
            strategy.start();
        } catch (Exception e) {
            log.error("Error crítico en la estrategia: {}", e.getMessage(), e);
            strategy.shutdown();
            System.exit(2);
        }
    }
}
