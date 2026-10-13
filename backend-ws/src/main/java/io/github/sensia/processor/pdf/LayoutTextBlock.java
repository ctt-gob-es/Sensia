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

/**
 * Bloque de texto visual formado por varias líneas consecutivas
 */
public final class LayoutTextBlock {

    /*
     * Evita que accidentalmente construyamos bloques enormes
     */
    private static final int MAX_LINES = 3;

    private final List<Line> lines = new ArrayList<>();

    public LayoutTextBlock(
            String text,
            List<TextPosition> positions) {

        lines.add(new Line(text, positions));
    }

    /**
     * Indica si la siguiente línea parece ser continuación visual
     * de la última línea del bloque.
     */
    public boolean canAppend(
            String text,
            List<TextPosition> positions) {

        if (lines.size() >= MAX_LINES) {
            return false;
        }

        if (!isIndexSafe(text, positions)) {
            return false;
        }

        Line previous = lines.get(lines.size() - 1);
        Line next = new Line(text, positions);


        if (angleDifference(previous.direction, next.direction) > 2f) {
            return false;
        }


        /*
         * Tamaño de fuente parecido.
         *
         * Evita unir una etiqueta pequeña con un valor grande situado debajo
         */
        float maxFont =
                Math.max(previous.fontSize, next.fontSize);

        float fontDifference =
                Math.abs(previous.fontSize - next.fontSize);

        if (fontDifference > Math.max(1f, maxFont * 0.25f)) {
            return false;
        }

        float dy = Math.abs(next.y - previous.y);

        float lineHeight =
                Math.max(previous.height, next.height);

        float minVerticalGap =
                Math.max(1f, lineHeight * 0.50f);

        float maxVerticalGap =
                Math.max(10f, lineHeight * 2.0f);

        if (dy < minVerticalGap || dy > maxVerticalGap) {
            return false;
        }

        /*
         * Continuidad horizontal. Se acepta aproximadamente el mismo comienzo X
         * o un solapamiento horizontal fuerte (permite una segunda línea indentada).
         */
        float averageWidth =
                Math.max(previous.averageCharWidth,
                         next.averageCharWidth);

        float xTolerance =
                Math.max(8f, averageWidth * 2.5f);

        boolean sameLeft =
                Math.abs(previous.x1 - next.x1) <= xTolerance;

        float overlap =
                Math.min(previous.x2, next.x2)
                - Math.max(previous.x1, next.x1);

        float smallerWidth =
                Math.min(
                        previous.x2 - previous.x1,
                        next.x2 - next.x1);

        boolean strongOverlap =
                smallerWidth > 0
                && overlap > 0
                && (overlap / smallerWidth) >= 0.65f;

        return sameLeft || strongOverlap;
    }

    public void append(
            String text,
            List<TextPosition> positions) {

        if (!canAppend(text, positions)) {
            throw new IllegalArgumentException(
                    "La línea no pertenece al bloque");
        }

        lines.add(new Line(text, positions));
    }

    public int lineCount() {
        return lines.size();
    }

    /**
     * Texto reconstruido
     */
    public String text() {

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {

            if (i > 0) {
                result.append('\n');
            }

            result.append(lines.get(i).text);
        }

        return result.toString();
    }

    /**
     * Lista alineada 1:1 con text().
     *
     * Para el '\n' repetimos la última posición de la línea
     * anterior. De esta forma:
     * text().length() == positions().size()
     *
     */
    public List<TextPosition> positions() {

        List<TextPosition> result = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {

            Line line = lines.get(i);

            if (i > 0) {

                Line previous = lines.get(i - 1);

                TextPosition anchor =
                        previous.positions.get(
                                previous.positions.size() - 1);

                result.add(anchor);
            }

            result.addAll(line.positions);
        }

        return result;
    }

    /**
     * Solo utilizamos bloques donde el String sanitizado y los
     * TextPosition mantienen correspondencia exacta.
     */
    public static boolean isIndexSafe(
            String text,
            List<TextPosition> positions) {

        return text != null
                && positions != null
                && !positions.isEmpty()
                && text.length() == positions.size();
    }

    private static float angleDifference(float a, float b) {

        float difference = Math.abs(a - b) % 360f;

        if (difference > 180f) {
            difference = 360f - difference;
        }

        return difference;
    }

/**
 * Indica si el fragmento sigue la última línea en la misma línea visual.
 * PDFBox a veces entrega una línea a trozos de pocos caracteres (texto girado o glifos
 * colocados uno a uno): sin unirlos, "CSV: 0008…" llega como "C", "S", "V", "00"…
 */
public boolean continuesLine(String text, List<TextPosition> positions) {
    if (!isIndexSafe(text, positions)) return false;
    Line previous = lines.get(lines.size() - 1);
    Line next = new Line(text, positions);
    if (angleDifference(previous.direction, next.direction) > 2f) return false;
    float lineHeight = Math.max(previous.height, next.height);
    if (Math.abs(next.y - previous.y) > lineHeight * 0.3f) return false;
    float charWidth = Math.max(previous.averageCharWidth, next.averageCharWidth);
    float gap = next.x1 - previous.x2;
    return gap > -charWidth * 0.5f && gap < charWidth * 1.5f;
}

/** Une el fragmento al final de la última línea; si hay hueco visible, con un espacio */
public void appendToLine(String text, List<TextPosition> positions) {
    Line previous = lines.remove(lines.size() - 1);
    Line next = new Line(text, positions);
    float charWidth = Math.max(previous.averageCharWidth, next.averageCharWidth);
    boolean espacio = next.x1 - previous.x2 > charWidth * 0.3f
            && !previous.text.endsWith(" ") && !text.startsWith(" ");

    StringBuilder t = new StringBuilder(previous.text);
    List<TextPosition> p = new ArrayList<>(previous.positions);
    if (espacio) {
        t.append(' ');
        p.add(previous.positions.get(previous.positions.size() - 1));   // ancla, como en el '\n'
    }
    List<Integer> starts = new ArrayList<>(previous.fragmentStarts);
    starts.add(t.length());
    t.append(text);
    p.addAll(positions);
    lines.add(new Line(t.toString(), p, starts));
}

/** Número de trozos de PDFBox que forman el bloque */
public int fragmentCount() {
    int n = 0;
    for (Line line : lines) n += line.fragmentStarts.size();
    return n;
}

/** Para cada carácter de text(), el trozo del que procede ('\n' cuenta con el anterior) */
public int[] fragmentIds() {
    int[] ids = new int[text().length()];
    int pos = 0, id = -1;
    for (int i = 0; i < lines.size(); i++) {
        Line line = lines.get(i);
        if (i > 0) ids[pos++] = id;   // '\n'
        for (int k = 0; k < line.text.length(); k++) {
            if (line.fragmentStarts.contains(k)) id++;
            ids[pos++] = id;
        }
    }
    return ids;
}


    private static final class Line {

        private final String text;
        private final List<TextPosition> positions;
        private final List<Integer> fragmentStarts;   // inicio de cada trozo de PDFBox en text

        private final float x1;
        private final float x2;
        private final float y;

        private final float height;
        private final float fontSize;
        private final float averageCharWidth;
        private final float direction;

        private Line(String text, List<TextPosition> positions) {
            this(text, positions, List.of(0));
        }
        

        private Line(String text, List<TextPosition> positions, List<Integer> fragmentStarts) {        

            this.text = text;
            this.positions = new ArrayList<>(positions);
            this.fragmentStarts = List.copyOf(fragmentStarts);

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;

            float totalY = 0f;
            float totalHeight = 0f;
            float totalFontSize = 0f;
            float totalWidth = 0f;
            float totalDirection = 0f;

            int count = 0;

            for (TextPosition position : positions) {

                float x = position.getXDirAdj();

                minX = Math.min(minX, x);

                maxX = Math.max(
                        maxX,
                        x + position.getWidthDirAdj());

                totalY += position.getYDirAdj();

                totalHeight +=
                        Math.max(
                                position.getHeightDir(),
                                position.getFontSizeInPt());

                totalFontSize +=
                        position.getFontSizeInPt();

                totalWidth +=
                        position.getWidthDirAdj();

                totalDirection +=
                        position.getDir();

                count++;
            }

            this.x1 = minX;
            this.x2 = maxX;

            this.y =
                    count > 0
                            ? totalY / count
                            : 0f;

            this.height =
                    count > 0
                            ? totalHeight / count
                            : 0f;

            this.fontSize =
                    count > 0
                            ? totalFontSize / count
                            : 0f;

            this.averageCharWidth =
                    count > 0
                            ? totalWidth / count
                            : 0f;

            this.direction =
                    count > 0
                            ? totalDirection / count
                            : 0f;
        }
    }
}