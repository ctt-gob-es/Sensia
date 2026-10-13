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


import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.processor.model.DetectionType;


/**
 * Gestor de seudónimos que mantiene la coherencia de las entidades 
 * a lo largo de todo un documento. Debe instanciarse por cada PDF.
 */
public class PseudonymManager {
    private static final Logger logger = LoggerFactory.getLogger(PseudonymManager.class);

    private final Map<String, String> diccionarioEntidades = new HashMap<>();
 
    private int contadorPersonas = 1;
    //private int contadorOrganizaciones = 1;
    private int contadorDatos = 1;

    private final boolean seudonimizacionActiva;

    public PseudonymManager(boolean seudonimizacionActiva) {
        this.seudonimizacionActiva = seudonimizacionActiva;
    }

    public boolean isActivo() {
        return seudonimizacionActiva;
    }

    /**
     * Devuelve el seudónimo correspondiente a un texto. Si no existe, lo crea.
     */
    public String obtenerOCrearSeudonimo(String textoOriginal, DetectionType tipo) {
        if (!seudonimizacionActiva || textoOriginal == null || textoOriginal.trim().isEmpty()) {
            return null; 
        }

        String clave = normalizarTexto(textoOriginal);

        if (diccionarioEntidades.containsKey(clave)) {
            return diccionarioEntidades.get(clave);
        }

        String nuevoSeudonimo = generarEtiqueta(tipo);
        
        diccionarioEntidades.put(clave, nuevoSeudonimo);
        logger.debug("Nuevo seudónimo generado: {} ({})", nuevoSeudonimo, tipo);

        return nuevoSeudonimo;
    }

    private String generarEtiqueta(DetectionType tipo) {
        switch (tipo) {
            case PERSON_NAME:
            case FACE:
                return "[SUJETO_" + (contadorPersonas++) + "]";
            case SENSITIVE_DATA:
                return "[DATO_" + (contadorDatos++) + "]";
            // Otros
            // case ORGANIZATION:
            //    return "[ENTIDAD_" + (contadorOrganizaciones++) + "]";
            default:
                return "[CENSURADO]";
        }
    }

    private String normalizarTexto(String texto) {
        String limpio = texto.trim().toLowerCase();
        limpio = Normalizer.normalize(limpio, Normalizer.Form.NFD);
        limpio = limpio.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return limpio;
    }
}