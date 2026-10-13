/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package io.github.sensia.ws;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.io.FileInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    private static final Map<String, ConfigManager> instances = new ConcurrentHashMap<>();

    private Map<String, String> configuracion;
    private final String configFileName;

    

    private ConfigManager(String fileName) {
        this.configFileName = fileName;
        loadProperties();
    }



    public static ConfigManager getInstance(String fileName) {
        return instances.computeIfAbsent(fileName, ConfigManager::new);
    }



    private void loadProperties() {
        Properties propiedades = new Properties();
        boolean loadedExternally = false;

        String confDir = System.getProperty("sensia.config.dir", ".");
        File externalFile = new File(confDir, configFileName);

        if (externalFile.exists() && externalFile.isFile()) {
            log.info("[Configuración externa] Cargando configuración EXTERNA desde: {}", externalFile.getAbsolutePath());
            try (InputStream input = new FileInputStream(externalFile)) {
                propiedades.load(input);
                loadedExternally = true;
            } catch (Exception e) {
                log.warn("No se pudo leer el archivo externo {}, intentando fallback interno...", externalFile.getAbsolutePath(), e);
            }
        }

        if (!loadedExternally) {
            log.info("[Configuración interna (classpath)] Cargando configuración INTERNA desde el classpath: {}", configFileName);
            try (InputStream input = ConfigManager.class.getClassLoader().getResourceAsStream(configFileName)) {
                if (input == null) {
                    log.error("ERROR: No se encontró el archivo de configuración '{}'", configFileName);
                    throw new RuntimeException("No se pudo encontrar el archivo " + configFileName);
                }
                propiedades.load(input);
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException("Error al cargar " + configFileName, e);
            }
        }

        Map<String, String> configTemporal = new HashMap<>();
        for (String clave : propiedades.stringPropertyNames()) {
            configTemporal.put(clave, propiedades.getProperty(clave));
        }

        configuracion = Map.copyOf(configTemporal);
    }



    public String getConfig(String name) {
        return getConfig(name, null);
    }



    public String getConfig(String name, String defaultValue) {
        String envName = name.toUpperCase().replace(".", "_");

        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue;
        }

        return configuracion.getOrDefault(name, defaultValue);
    }



    public Boolean getBooleanConfig(String key, boolean defaultValue) {
        String val = getConfig(key);
        return (val != null && !val.trim().isEmpty()) ? Boolean.valueOf(val) : defaultValue;
    }




    public Float getFloatConfig(String key, float defaultValue) {
        String val = getConfig(key);
        if (val == null || val.trim().isEmpty())
            return defaultValue;
        try {
            return Float.valueOf(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }




    public Integer getIntConfig(String key, int defaultValue) {
        String val = getConfig(key);
        if (val == null || val.trim().isEmpty())
            return defaultValue;
        try {
            return Integer.valueOf(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }


   
    public float[] getRectConfig(String key, float[] defaultValue) {
        String val = getConfig(key);
        if (val == null || val.trim().isEmpty()) {
            return defaultValue;
        }

        String[] parts = val.split(",");
        if (parts.length != 4) {
            log.warn("Formato de rect inválido para {}. Se esperaba x,y,w,h. Se usa default.", key);
            return defaultValue;
        }

        try {
            float[] rect = new float[4];
            rect[0] = Float.parseFloat(parts[0].trim()); // X
            rect[1] = Float.parseFloat(parts[1].trim()); // Y
            rect[2] = Float.parseFloat(parts[2].trim()); // Width (W)
            rect[3] = Float.parseFloat(parts[3].trim()); // Height (H)
            return rect;
        } catch (NumberFormatException e) {
            log.warn("Error parseando números del rect para {}. Se usa default.", key);
            return defaultValue;
        }
    }
    

}