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

final class NerSpanUtils {

    private NerSpanUtils() {}

    /**
     * Ajusta [start, end) de una entidad NER a palabras completas de 'texto'.
     * Primero recorta los extremos que no son palabra y después amplía hasta los
     * límites de palabra. El recorte previo es necesario: los tokenizadores BPE
     * (como el RoBERTa del BSC) suelen incluir el espacio anterior en el offset, y
     * ampliar desde un espacio arrastraría la palabra anterior ("BANCO BILBAO").
     * Si no queda nada, devuelve un span vacío.
     */
    static int[] ajustarALimitesDePalabra(String texto, int start, int end) {
        int n = texto.length();
        int s = Math.max(0, Math.min(start, n));
        int e = Math.max(s, Math.min(end, n));

        while (s < e && !esParteDePalabra(texto, s)) s++;
        while (e > s && !esParteDePalabra(texto, e - 1)) e--;
        if (s == e) {
            return new int[] { s, s };
        }

        while (s > 0 && esParteDePalabra(texto, s - 1)) s--;
        while (e < n && esParteDePalabra(texto, e)) e++;
        return new int[] { s, e };
    }

    static int contarAlfanumericos(String s) {
        int total = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) total++;
        }
        return total;
    }

        private static boolean esApostrofo(char c) {
        // Apóstrofo recto, tipográfico y acento agudo (U+00B4), que en los documentos
        // en valenciano se usa como apóstrofo: D´ELX, d´Atenció
        return c == '\'' || c == '\u2019' || c == '\u00B4';
    }

    static boolean esParteDePalabra(String t, int i) {
        char c = t.charAt(i);
        if (Character.isLetterOrDigit(c)) return true;
        int tipo = Character.getType(c);
        if (tipo == Character.NON_SPACING_MARK || tipo == Character.COMBINING_SPACING_MARK) return true;
        // Guion o apóstrofo solo cuentan si están entre dos letras: GARCÍA-LÓPEZ, D´ELX
        if (c == '-' || esApostrofo(c)) {
            return i > 0 && i < t.length() - 1
                    && Character.isLetter(t.charAt(i - 1))
                    && Character.isLetter(t.charAt(i + 1));
        }
        return false;
    }

    /**
     * El NER (Python) da las posiciones en caracteres Unicode (code points) y String
     * indexa en UTF-16. Solo difieren si hay caracteres fuera del plano básico.
     */
    static int aIndiceUtf16(String texto, int codePoint) {
        int total = texto.codePointCount(0, texto.length());
        return texto.offsetByCodePoints(0, Math.max(0, Math.min(codePoint, total)));
    }
    
}