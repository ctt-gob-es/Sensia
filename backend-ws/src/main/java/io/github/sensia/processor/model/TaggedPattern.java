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


package io.github.sensia.processor.model;

import java.util.regex.Pattern;

public class TaggedPattern {
    private final String tag;
    private final Pattern pattern;

    public TaggedPattern(String tag, Pattern pattern) {
        this.tag = tag;
        this.pattern = pattern;
    }

    public String getTag() {
        return tag;
    }

    public Pattern getPattern() {
        return pattern;
    }
}