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


package io.github.sensia.processor.pdf;


import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record SanitizedText(String text, List<TextPosition> positions) {

    private static final Logger logger = LoggerFactory.getLogger(SanitizedText.class);
    private static final Logger logSensible = LoggerFactory.getLogger("sensia.sensible.pdf");

    // Texto que aporta el glifo en esta posición: su Unicode tal cual o normalizado
    // (PDFBox convierte ligaduras como U+FB01 en "fi" al montar la cadena)
    private static String textoDelGlifo(String raw, int i, String unicode) {
        if (unicode == null || unicode.isEmpty()) return null;
        if (raw.startsWith(unicode, i)) return unicode;
        String nfkc = java.text.Normalizer.normalize(unicode, java.text.Normalizer.Form.NFKC);
        return raw.startsWith(nfkc, i) ? nfkc : null;
    }

    public static SanitizedText process(String rawString, List<TextPosition> rawPositions) {
        StringBuilder cleanString = new StringBuilder();
        List<TextPosition> cleanPositions = new ArrayList<>();

        int posIndex = 0;

        for (int i = 0; i < rawString.length(); i++) {
            char c = rawString.charAt(i);
            TextPosition currentPos = (posIndex < rawPositions.size()) ? rawPositions.get(posIndex) : null;

            if (c == ' ' && (currentPos == null || !" ".equals(currentPos.getUnicode()))) {
                if (posIndex > 0 && posIndex < rawPositions.size()) {
                    TextPosition prev = rawPositions.get(posIndex - 1);
                    TextPosition next = rawPositions.get(posIndex);
                    float gap = next.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());

                    if (gap < 1.5f && gap > -5.0f) {
                        logSensible.debug("Kerning (espacio inyectado) corregido entre '{}' y '{}'", prev.getUnicode(), next.getUnicode());
                        continue;
                    }
                }
                // Espacio sin glifo: se ancla a la posición anterior para mantener texto y posiciones 1:1
                if (posIndex > 0) {
                    cleanString.append(c);
                    cleanPositions.add(rawPositions.get(posIndex - 1));
                }                
                continue;
            }

            String glifo = currentPos == null ? null : textoDelGlifo(rawString, i, currentPos.getUnicode());
            if (glifo != null) {
                if (c == ' ' && posIndex > 0 && posIndex < rawPositions.size() - 1) {
                    TextPosition prev = rawPositions.get(posIndex - 1);
                    TextPosition next = rawPositions.get(posIndex + 1);
                    float gap = next.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());

                    if (gap < 1.5f && gap > -5.0f) {
                        logSensible.debug("Kerning (espacio físico) corregido entre '{}' y '{}'", prev.getUnicode(), next.getUnicode());
                        posIndex++;
                        continue;  
                    }
                }
                
                // Un glifo puede dar varios caracteres (ligaduras): se copian todos y se repite su posición
                cleanString.append(glifo);
                for (int k = 0; k < glifo.length(); k++) cleanPositions.add(currentPos);
                i += glifo.length() - 1;                
                posIndex++;
            } else {
                cleanString.append(c);
            }
        }

        return new SanitizedText(cleanString.toString(), cleanPositions);
    }
}