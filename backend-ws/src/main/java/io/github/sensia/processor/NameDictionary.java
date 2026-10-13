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
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NameDictionary {
    private static final Logger logger = LoggerFactory.getLogger(NameDictionary.class);
    
    //private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("[\\p{M}&&[^\\u0303]]");

    
    private final Set<String> knownNames;

    public NameDictionary(String fileName) {
        this.knownNames = Set.copyOf(loadNames(fileName));
    }

    private Set<String> loadNames(String fileName) {
        Set<String> names = new HashSet<>();

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        try (InputStream is = classLoader.getResourceAsStream(fileName)) {
            if (is == null) {
                throw new IllegalStateException("No se encontró " + fileName + " en el classpath");                
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toUpperCase();

                    String cleanLine = limpiarTexto(line);
                    
                    if (cleanLine != null && cleanLine.length() > 1 && !cleanLine.startsWith("#")) {
                        names.add(cleanLine);
                    }

                }
            }
            logger.info("Diccionario cargado con {} nombres/apellidos.", names.size());
        } catch (Exception e) {
            logger.error("Error cargando diccionario de nombres", e);
        }
        
        return names;
    }



    public boolean isName(String word) {
        if (word == null) return false;
        return knownNames.contains(limpiarTexto(word.toUpperCase()));
    }




    private String limpiarTexto(String input) {
        if (input == null) return null;

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        
        String sinAcentos = DIACRITICS_PATTERN.matcher(normalized).replaceAll("");
        
        return sinAcentos.toUpperCase().trim();
    }
}