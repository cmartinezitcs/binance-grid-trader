package com.gridtrader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gridtrader.model.GridState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class StateManager {

    private static final Logger log = LoggerFactory.getLogger(StateManager.class);

    private final String filePath;
    private final ObjectMapper mapper;

    public StateManager(String filePath) {
        this.filePath = filePath;
        this.mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(GridState state) {
        state.setLastUpdateTime(System.currentTimeMillis());
        try {
            mapper.writeValue(new File(filePath), state);
            log.debug("Estado guardado en {}", filePath);
        } catch (IOException e) {
            log.error("Error guardando estado en {}: {}", filePath, e.getMessage());
        }
    }

    public GridState load() {
        File file = new File(filePath);
        if (!file.exists()) {
            log.info("No hay estado previo en {}. Iniciando nuevo grid.", filePath);
            return null;
        }
        try {
            GridState state = mapper.readValue(file, GridState.class);
            log.info("Estado previo cargado desde {} (actualizado: {})",
                filePath, new java.util.Date(state.getLastUpdateTime()));
            return state;
        } catch (IOException e) {
            log.warn("Error cargando estado previo: {}. Iniciando nuevo grid.", e.getMessage());
            return null;
        }
    }

    public void delete() {
        File file = new File(filePath);
        if (file.exists() && file.delete()) {
            log.info("Estado previo eliminado: {}", filePath);
        }
    }

    public boolean exists() {
        return new File(filePath).exists();
    }
}
