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

package io.github.sensia.processor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.processor.model.TaggedPattern;
import io.github.sensia.processor.ocr.OcrResourcesManager;

public class CensorResources {

    private static final Logger log = LoggerFactory.getLogger(CensorResources.class);

    private static volatile CensorResources instancia;

    private final List<TaggedPattern> patrones;
    private final List<String> listaBlanca;
    private final NameDictionary diccionarioNombres;
    private final OcrResourcesManager ocrManager;
    private final NerClient nerClient;
    
    private CensorResources() {
        log.info("Cargando recursos del Censor en memoria...");

        this.ocrManager = new OcrResourcesManager();
        this.nerClient = new NerClient();
        this.patrones = List.copyOf(cargarPatrones());
        this.listaBlanca = List.copyOf(cargarListaBlanca());
        
        this.diccionarioNombres = new NameDictionary("nombres_y_apellidos.txt");
        
        log.info("Recursos cargados: {} patrones, {} en whitelist.", patrones.size(), listaBlanca.size());
    }

    public static CensorResources getInstance() {

        if (instancia == null) {
            synchronized (CensorResources.class) {
                if (instancia == null) {
                    instancia = new CensorResources();
                }
            }
        }
        return instancia;
    }

    public List<TaggedPattern> getPatrones() { return patrones; }
    public List<String> getListaBlanca() { return listaBlanca; }
    public NameDictionary getDiccionarioNombres() { return diccionarioNombres; }
    public OcrResourcesManager getOcrManager() { return ocrManager; }

    private List<String> cargarListaBlanca() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        try (InputStream is = classLoader.getResourceAsStream("lista_blanca.txt")) {
            if (is == null) {
                log.warn("No se encontró lista_blanca.txt. Se iniciará sin excepciones.");
                return new ArrayList<>();
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Error leyendo lista_blanca.txt", e);
            return new ArrayList<>();
        }
    }

    private List<TaggedPattern> cargarPatrones() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        
        try (InputStream is = classLoader.getResourceAsStream("patrones.txt")) {
            if (is == null) {
                throw new IllegalStateException("ERROR: No se encontró patrones.txt en el classpath.");
            }
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                List<TaggedPattern> patterns = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .map(line -> {
                            String tag = "DATO_SENSIBLE"; // Etiqueta por defecto
                            String regex = line;
                            
                            // Extraer la etiqueta si existe el separador "::"
                            if (line.contains("::")) {
                                String[] parts = line.split("::", 2);
                                tag = parts[0].trim();
                                regex = parts[1].trim();
                            }
                            
                            return new TaggedPattern(tag, Pattern.compile(regex));
                            
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                        
                if (patterns.isEmpty()) {
                    throw new IllegalStateException("ERROR: El archivo patrones.txt está vacío o todos los patrones son inválidos.");
                }
                
                return patterns;
            }
        } catch (Exception e) {
            throw new RuntimeException("ERROR cargando los patrones de censura.", e);
        }
    }

    public void liberarRecursos() {
        log.info("Limpiando recursos globales del Singleton...");
        
        if (this.ocrManager != null) {
            try {
                this.ocrManager.close();
                log.info("OcrResourcesManager apagado correctamente.");
            } catch (Exception e) {
                log.error("Error apagando OcrResourcesManager", e);
            }
        }
        
        if (this.nerClient != null) {
            try {
                this.nerClient.shutdown();
                log.info("NerClient apagado correctamente.");
            } catch (Exception e) {
                log.error("Error apagando NerClient", e);
            }
        }   

    }

    public NerClient getNerClient() {
        return nerClient;
    }
}