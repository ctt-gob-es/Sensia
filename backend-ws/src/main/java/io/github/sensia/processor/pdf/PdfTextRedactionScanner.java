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

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.processor.model.dto.RedactionItemDTO;
import ner.Entity;
import io.github.sensia.processor.model.dto.CoordinateDTO;
import io.github.sensia.processor.model.dto.ActionStateDTO;
import io.github.sensia.processor.NameDictionary;
import io.github.sensia.processor.NerClient;
import io.github.sensia.processor.PseudonymManager;
import io.github.sensia.processor.SensiaException;
import io.github.sensia.processor.model.DetectionType;
import io.github.sensia.processor.model.ProcessState;
import io.github.sensia.processor.model.TaggedPattern;

public class PdfTextRedactionScanner extends PDFTextStripper {

    private static final Logger logger = LoggerFactory.getLogger(PdfTextRedactionScanner.class);
    private static final Logger logSensible = LoggerFactory.getLogger("sensia.sensible.pdf");

    private static final boolean ENABLE_MULTILINE_LAYOUT = true;
    private static final Pattern NAME_PATTERN = Pattern.compile("(?U)(?:[A-ZÑÁÉÍÓÚ]{2,}|[A-ZÑÁÉÍÓÚ][a-zñáéíóú]+)");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final int NER_MIN_CHARS = 3;

    private final float defaultSpacingTolerance;
    private final float defaultAverageCharTolerance;

    private final List<TaggedPattern> patterns;
    private final NameDictionary nameDict;
    private final NerClient nerClient;
    private final List<String> literalWhitelist;
    private final List<Pattern> regexWhitelist;
    private final PseudonymManager pseudoManager;
    private final boolean redactNames;
    private final boolean redactNERNames;
    private final boolean redactNEROrg;
    private final boolean redactNERLoc;
    private final boolean redactNERMisc;
    private final double redactNERScore;
    private final List<RedactionItemDTO> itemsFound = new ArrayList<>();
    private int currentPage;
    private String documentHash;
    private final List<LayoutTextBlock> layoutBlocks = new ArrayList<>();
    private LayoutTextBlock currentLayoutBlock;
    private boolean lineBreakPending = false;
    // PDFBox da las posiciones relativas a la esquina inferior izquierda del CropBox;
    // las censuras se dibujan en el espacio de usuario de la página
    private float origenCropX = 0f;
    private float origenCropY = 0f;

    public PdfTextRedactionScanner(List<TaggedPattern> patterns, NameDictionary dict,
            List<String> literalWhitelist, List<Pattern> regexWhitelist, PseudonymManager pseudoManager,
            NerClient nerClient,
            boolean redactNames, boolean redactNERNames, boolean redactNEROrg, boolean redactNERLoc, boolean redactNERMisc, double redactNERScore,
            int currentPage, String documentHash) throws IOException {

            boolean useNer = redactNERNames || redactNEROrg || redactNERLoc || redactNERMisc;
            if (useNer && nerClient == null) {
                throw new SensiaException(ProcessState.ERROR,
                            "Se ha solicitado reconocimiento de entidades (NER), pero no está disponible en esta instalación. "
                            + "Desactive las opciones NER.");
            }

        this.defaultSpacingTolerance = super.getSpacingTolerance();
        this.defaultAverageCharTolerance = super.getAverageCharTolerance();

        this.patterns = patterns;
        this.nameDict = dict;
        this.pseudoManager = pseudoManager;
        this.nerClient = nerClient;
        // this.literalWhitelist = literalWhitelist;
        this.literalWhitelist = new ArrayList<>();
        for (String item : literalWhitelist) {
            String limpio = Normalizer.normalize(item.toLowerCase().trim(), Normalizer.Form.NFD);
            limpio = DIACRITICS_PATTERN.matcher(limpio).replaceAll("");
            this.literalWhitelist.add(limpio);
        }
        this.regexWhitelist = regexWhitelist;
        this.redactNames = redactNames;
        this.redactNERNames = redactNERNames;
        this.redactNEROrg = redactNEROrg;
        this.redactNERLoc = redactNERLoc;
        this.redactNERMisc = redactNERMisc;
        this.redactNERScore = redactNERScore;
        this.currentPage = currentPage;
        this.documentHash = documentHash;
        super.setSortByPosition(!ENABLE_MULTILINE_LAYOUT);
        super.setStartPage(currentPage);
        super.setEndPage(currentPage);

    }

    public List<RedactionItemDTO> getItemsFound() {
        eliminarSuperposiciones(itemsFound);
        return itemsFound;
    }

    private boolean estaEnListaBlanca(String textoDetectado) {
        String detectadoLimpio = textoDetectado.toLowerCase().trim();

        detectadoLimpio = Normalizer.normalize(detectadoLimpio, Normalizer.Form.NFD);

        detectadoLimpio = DIACRITICS_PATTERN.matcher(detectadoLimpio).replaceAll("");

        for (Pattern regexSegura : regexWhitelist) {
            if (regexSegura.matcher(detectadoLimpio).matches()) {
                logSensible.debug("Salvado por lista blanca (regex {}): {}", regexSegura.pattern(), textoDetectado);
                return true;
            }
        }

        for (String itemSeguroLimpio : literalWhitelist) {
            if (detectadoLimpio.equals(itemSeguroLimpio)) {
                logSensible.debug("Salvado por lista blanca (literal): {}", textoDetectado);
                return true;
            }
        }

        return false;
    }

    @Override
    public void processPage(PDPage page) throws IOException {
        layoutBlocks.clear();
        currentLayoutBlock = null;
        lineBreakPending = false;
        origenCropX = page.getCropBox().getLowerLeftX();
        origenCropY = page.getCropBox().getLowerLeftY();

        if (tieneFuenteProblematica(page)) {
            logger.debug("Ajustando tolerancia por fuentes problemáticas en pág {}", this.currentPage);
            super.setSpacingTolerance(2.0f);
            super.setAverageCharTolerance(1.5f);
        } else {
            super.setSpacingTolerance(this.defaultSpacingTolerance);
            super.setAverageCharTolerance(this.defaultAverageCharTolerance);
        }

        super.processPage(page);

         if (ENABLE_MULTILINE_LAYOUT) {
             // Segunda pasada exclusivamente para bloques que tengan varias líneas.
            logger.info("LAYOUT: página {} -> {} bloques", currentPage, layoutBlocks.size());
            for (LayoutTextBlock block : layoutBlocks) {

                if (block.fragmentCount() < 2) {
                    continue;
                }

                analizarPatrones(block.text(), block.positions(), block.fragmentIds());
            }
        }

       
    }

    // fragmentos: null en la pasada normal; en bloques, el trozo de PDFBox de cada carácter
    private void analizarPatrones(String string, List<TextPosition> textPositions, int[] fragmentos)
    {

        for (TaggedPattern taggedPattern : patterns) {
            Pattern pattern = taggedPattern.getPattern();
            String tagLabel = taggedPattern.getTag();
            String textoAnalizar = (string.length() > 5000) ? string.substring(0, 5000) : string;
            Matcher matcher = pattern.matcher(textoAnalizar);

            // logger.debug("==================== pattern =========================");
            // logger.debug("pattern: " + pattern.pattern());
            // logSensible.debug("texto analizar: " + textoAnalizar);
            // logger.debug("texto analizar: " + textoAnalizar.length());

            while (matcher.find()) {

                // En bloques solo cuentan las coincidencias que unen trozos distintos:
                // las de un único trozo ya se analizaron en la pasada normal
                if (fragmentos != null && (matcher.end() <= matcher.start()
                        || fragmentos[matcher.start()] == fragmentos[matcher.end() - 1])) {
                    continue;
                }
            
                String textoEncontrado = matcher.group();

                logSensible.debug("Coincidencia [{}] (pág. {}): {}", tagLabel, currentPage, textoEncontrado);

                int realStart = resolveIndexDrift(matcher.start(), textoEncontrado, textPositions);
                int driftOffset = matcher.start() - realStart;
                int realEnd = matcher.end() - driftOffset;

                boolean esRegexNombres = tagLabel.equals("NOMBRE")
                        || pattern.pattern().contains("\\b(?:[A-ZÑÁÉÍÓÚ]{2,}|[A-ZÑÁÉÍÓÚ][a-zñáéíóú]+)");
                if (estaEnListaBlanca(textoEncontrado)) {
                    acumularRectangulos(textPositions, realStart, realEnd,
                            DetectionType.SENSITIVE_DATA, tagLabel, "Coincidencia patrón: " + pattern.pattern(),
                            textoEncontrado,
                            ActionStateDTO.WHITELISTED);

                } else if (esRegexNombres && redactNames) {
                    Matcher palabraMatcher = NAME_PATTERN.matcher(textoEncontrado);
                    int countNombres = 0;
                    int countEnDict = 0;
                    // Contamos cuántas palabras del bloque detectado están en el diccionario
                    while (palabraMatcher.find()) {
                        String palabra = palabraMatcher.group();

                        // Ignoramos partículas cortas (DE, LA, Y, etc.) y listas blancas locales
                        if (palabra.length() <= 2)
                            continue;
                        if (estaEnListaBlanca(palabra)) {
                            continue;
                        }

                        countNombres++;

                        // Si está en el diccionario, sumamos al contador
                        if (nameDict.isName(palabra)) {
                            logSensible.debug("Palabra en diccionario: {}", palabra);

                            countEnDict++;
                        }
                    }

                    // Censurar si hay al menos 50% en diccionario
                    float porcentajeEnDict = countNombres > 0 ? (float) countEnDict / countNombres : 0;
                    if (estaEnListaBlanca(textoEncontrado)) {
                        acumularRectangulos(textPositions, realStart, realEnd,
                                DetectionType.PERSON_NAME, "", "Nombre detectado", textoEncontrado,
                                ActionStateDTO.WHITELISTED);
                    } else if (countEnDict >= 2 && porcentajeEnDict >= 0.5) {
                        logSensible.debug("Nombre censurado (pág. {}): {}", currentPage, textoEncontrado.trim());
                        acumularRectangulos(textPositions, realStart, realEnd,
                                DetectionType.PERSON_NAME, "", "Nombre detectado", textoEncontrado,
                                ActionStateDTO.REDACTED);
                    } else {
                        logSensible.debug("Descartado (menos de 2 nombres): {}", textoEncontrado);
                    }

                } else if (!esRegexNombres) {
                    // Otros patrones que no son nombres (ej. DNIs, tarjetas, emails)
                    logSensible.debug("Dato sensible [{}] (pág. {}): {}", tagLabel, currentPage, textoEncontrado);
                    acumularRectangulos(textPositions, realStart, realEnd,
                            DetectionType.SENSITIVE_DATA, tagLabel, "Dato sensible detectado", textoEncontrado,
                            ActionStateDTO.REDACTED);
                }
            }
        }
    }


    private void registrarFragmentoLayout(
            String string,
            List<TextPosition> textPositions) {

        if (string == null
                || string.isBlank()
                || textPositions == null
                || textPositions.isEmpty()) {

            lineBreakPending = false;
            return;
        }

        boolean safe = LayoutTextBlock.isIndexSafe(
                string,
                textPositions
        );

        logger.debug("LAYOUT INPUT len={} pos={} break={} safe={} current={}",
                string.length(), textPositions.size(), lineBreakPending, safe, currentLayoutBlock != null);
        logSensible.debug("LAYOUT INPUT text=[{}]", string.replace("\n", "\\n"));

        if (!safe) {

            logSensible.debug(
                "LAYOUT REJECT INDEX text=[{}]",
                string.replace("\n", "\\n")
            );

            currentLayoutBlock = null;
            lineBreakPending = false;
            return;
        }

        // Trozos de una misma línea visual que PDFBox entrega por separado
        if (currentLayoutBlock != null && currentLayoutBlock.continuesLine(string, textPositions)) {
            currentLayoutBlock.appendToLine(string, textPositions);
            logSensible.debug("LAYOUT JOIN RESULT=[{}]", currentLayoutBlock.text().replace("\n", "\\n"));
            lineBreakPending = false;
            return;
        }

        if (lineBreakPending && currentLayoutBlock != null) {

            boolean append = currentLayoutBlock.canAppend(
                    string,
                    textPositions
            );

            logSensible.debug(
                "LAYOUT CAN_APPEND text=[{}] -> {}",
                string.replace("\n", "\\n"),
                append
            );

            if (append) {

                currentLayoutBlock.append(
                        string,
                        textPositions
                );

                logSensible.debug(
                    "LAYOUT APPEND RESULT=[{}]",
                    currentLayoutBlock.text()
                            .replace("\n", "\\n")
                );

                lineBreakPending = false;
                return;
            }
        }

        currentLayoutBlock =
                new LayoutTextBlock(
                        string,
                        textPositions
                );

        layoutBlocks.add(currentLayoutBlock);

        logSensible.debug(
            "LAYOUT NEW BLOCK=[{}]",
            string.replace("\n", "\\n")
        );

        lineBreakPending = false;
    }


    @Override
    protected void writeLineSeparator() throws IOException {


        if (ENABLE_MULTILINE_LAYOUT) {
            logger.trace("LAYOUT LINE BREAK");
            lineBreakPending = true;
        }

        super.writeLineSeparator();
    }
        

    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException {

        SanitizedText sanitized = SanitizedText.process(string, textPositions);
        string = sanitized.text();
        textPositions = sanitized.positions();
        
        if (ENABLE_MULTILINE_LAYOUT) {
            registrarFragmentoLayout(string, textPositions);
        }

        if (string.length() > 500) {
            long espacios = string.chars().filter(ch -> ch == ' ').count();
            if (espacios < 5) {
                logSensible.debug("Descartado por falta de espacios (pág. {}): {}", currentPage, string);
                return;
            }
        }
        // if (string.length() > 50 && string.matches(".*(.)\\1{20,}.*")){
        if (string.length() > 50 && string.matches("^[\\s\\.\\-_\\*]+$")) {
            logger.warn("Fragmento descartado por desajuste de posiciones (pág. {}, {} car.)", currentPage, string.length());
            logSensible.warn("Fragmento descartado por desajuste de posiciones (pág. {}): {}", currentPage, string);
            return;
        }
        if (string.length() < textPositions.size()) {
            logger.warn("Fragmento descartado por desajuste de posiciones (pág. {}, {} car., {} posiciones)",
                    currentPage, string.length(), textPositions.size());
            logSensible.warn("Descartado por TEXTPOSITIONS (pág. {}): {}", currentPage, string);
            return;
        }

        if (string.trim().isEmpty()){
            logger.trace("Descartado por falta de contenido");
            return;
        }

        // logger.debug("==================== Analizando =========================");
        // logger.debug("texto completo: " + string);
        //logger.info("STRING: [{}]", string);
        //for (TextPosition tp : textPositions) {
        //    logger.debug(
        //            "'{}' x={} y={} w={}",
        //            tp.getUnicode(),
        //            tp.getXDirAdj(),
        //            tp.getYDirAdj(),
        //            tp.getWidthDirAdj());
        //}

        analizarPatrones(
            string,
            textPositions, null);
        
        boolean useNer = redactNERNames || redactNEROrg || redactNERLoc || redactNERMisc;
        // Sin NER para trozos sin texto suficiente, sus entidades se descartarían igualmente
        if (useNer && NerSpanUtils.contarAlfanumericos(string) >= NER_MIN_CHARS) {
            procesarEntidadesNer(string, textPositions);
        }
    }

        private boolean categoriaNerActiva(String categoria) {
        if (categoria == null) return false;
        return switch (categoria) {
            case "PER"  -> redactNERNames;
            case "ORG"  -> redactNEROrg;
            case "LOC"  -> redactNERLoc;
            case "OTH" -> redactNERMisc;
            default     -> false;
        };
    }

    private void procesarEntidadesNer(String string, List<TextPosition> textPositions) {
        List<ner.Entity> entidades;
        try {
            entidades = nerClient.procesarTexto(string);
        } catch (Exception e) {
            logger.error("NER sin respuesta en pág. {}", currentPage);
            throw new SensiaException(ProcessState.ERROR,
                "El reconocimiento de entidades (NER) no ha respondido. "
                + "Vuelva a intentarlo más tarde o desactive las opciones NER.", e);
        }

        Set<Long> spansVistos = new HashSet<>();
        // Solo hay que convertir posiciones si el texto tiene caracteres fuera del plano básico
        boolean convertir = string.codePointCount(0, string.length()) != string.length();

        for (Entity entidad : entidades) {
            String cat = entidad.getCategory();
            double score = entidad.getScore();

            int ini = entidad.getStartChar(), fin = entidad.getEndChar();
            if (convertir) {
                ini = NerSpanUtils.aIndiceUtf16(string, ini);
                fin = NerSpanUtils.aIndiceUtf16(string, fin);
            }
            int[] span = NerSpanUtils.ajustarALimitesDePalabra(string, ini, fin);
            String texto = string.substring(span[0], span[1]);

            if (!categoriaNerActiva(cat) || score < redactNERScore) {
                logger.debug("NER descartada: cat={} score={} pág={}", cat, score, currentPage);
                logSensible.debug("NER descartada: cat={} score={} pág={} texto={}", cat, score, currentPage, texto);
                continue;
            }

            if (NerSpanUtils.contarAlfanumericos(texto) < NER_MIN_CHARS) {
                logger.debug("NER descartada por longitud: cat={} len={} score={} pág={}",
                        cat, texto.length(), score, currentPage);
                continue;
            }

            // Varios subtokens de una misma palabra acaban en el mismo span: una sola caja
            if (!spansVistos.add(((long) span[0] << 32) | span[1])) {
                continue;
            }

            // La lista blanca se evalúa ya sobre la palabra completa, no sobre el subtoken
            if (estaEnListaBlanca(texto)) {
                continue;
            }

            int realStart = resolveIndexDrift(span[0], texto, textPositions);
            int realEnd = span[1] - (span[0] - realStart);

            logger.debug("NER aceptada: cat={} len={} score={} pág={}", cat, texto.length(), score, currentPage);
            logSensible.debug("NER aceptada: cat={} texto={} score={} pág={}", cat, texto, score, currentPage);
            acumularRectangulos(textPositions, realStart, realEnd,
                    DetectionType.NAMED_ENTITY, cat, "Entidad detectada por IA",
                    texto, ActionStateDTO.REDACTED);
        }
    }


    // Una caja por línea: en coincidencias que cruzan un salto, una sola caja abarcaría
    // el ancho de ambas líneas y taparía texto ajeno a la coincidencia
    private void acumularRectangulos(List<TextPosition> textPositions, int start, int end,
            DetectionType type, String subType, String description, String matchedText, ActionStateDTO action) {
        int fin = Math.min(end, textPositions.size());
        int inicioLinea = start;
        for (int k = start + 1; k < fin; k++) {
            if (cambiaDeLinea(textPositions.get(k - 1), textPositions.get(k))) {
                anadirCaja(textPositions, inicioLinea, k, type, subType, description, matchedText, action);
                inicioLinea = k;
            }
        }
        anadirCaja(textPositions, inicioLinea, end, type, subType, description, matchedText, action);
    }

    private static boolean cambiaDeLinea(TextPosition a, TextPosition b) {
        float tol = Math.max(1f, Math.min(a.getFontSizeInPt(), b.getFontSizeInPt()) * 0.5f);
        return Math.abs(a.getYDirAdj() - b.getYDirAdj()) > tol;
    }


    private void anadirCaja(List<TextPosition> textPositions, int start, int end,
            DetectionType type, String subType, String description, String matchedText, ActionStateDTO action) {

        if (start >= end || start >= textPositions.size()) {
            logger.warn("FALLO DE ÍNDICES (pág. {}): índices {}-{}, la lista tiene {} posiciones",
                    currentPage, start, end, textPositions.size());
            logSensible.warn("FALLO DE ÍNDICES (pág. {}): texto {}", currentPage, matchedText);
            return;
        }

        // Caja que envuelve la coincidencia
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;

        for (int k = start; k < end; k++) {
            if (k >= textPositions.size())
                break;
            TextPosition pos = textPositions.get(k);

            float paddingExtra = 1.0f;

            float[] caja = calcularCajaTexto(pos, paddingExtra);
            float rectX = caja[0];
            float rectY = caja[1];
            float rectW = caja[2];
            float rectH = caja[3];

            if (rectX < minX)
                minX = rectX;
            if (rectY < minY)
                minY = rectY;
            if (rectX + rectW > maxX)
                maxX = rectX + rectW;
            if (rectY + rectH > maxY)
                maxY = rectY + rectH;
        }

        // Calculamos el ancho y alto final de la frase/palabra completa
        float finalW = maxX - minX;
        float finalH = maxY - minY;

        StringBuilder cubierto = new StringBuilder();
        for (int k = start; k < Math.min(end, textPositions.size()); k++) {
            cubierto.append(textPositions.get(k).getUnicode());
        }
        logSensible.debug("Caja (pág. {}) texto=[{}] cubierto=[{}] idx {}-{} de {} -> x={} y={} w={} h={}",
                currentPage, matchedText, cubierto, start, end, textPositions.size(),
                minX, minY, finalW, finalH);

        CoordinateDTO coord = new CoordinateDTO(minX, minY, finalW, finalH);
        String seudonimo = pseudoManager.obtenerOCrearSeudonimo(matchedText, type);
        RedactionItemDTO item = new RedactionItemDTO(
                type,
                subType,
                action,
                description,
                matchedText,
                currentPage,
                coord,
                documentHash,
                true, seudonimo);

        itemsFound.add(item);
    }

    private int resolveIndexDrift(int originalStart, String matchedText, List<TextPosition> textPositions) {
        String cleanText = matchedText.trim();

        if (cleanText.isEmpty() || originalStart >= textPositions.size() || originalStart < 0) {
            return originalStart;
        }

        // para casos raros donde se cuelan carateres artificiales
        String targetChar = String.valueOf(cleanText.charAt(0));
        int maxLookBehind = 15;

        for (int driftOffset = 0; driftOffset <= maxLookBehind && (originalStart - driftOffset) >= 0; driftOffset++) {
            TextPosition currentPosition = textPositions.get(originalStart - driftOffset);
            String visualChar = currentPosition.getUnicode();

            if (visualChar != null && visualChar.equals(targetChar)) {
                return originalStart - driftOffset;
            }
        }

        return originalStart;
    }

    private float[] calcularCajaTexto(org.apache.pdfbox.text.TextPosition pos, float paddingExtra) {
        float rawX = pos.getTextMatrix().getTranslateX() + origenCropX;
        float rawY = pos.getTextMatrix().getTranslateY() + origenCropY;
        float fontSize = pos.getFontSizeInPt();
        float width = pos.getWidthDirAdj();
        int angle = (int) pos.getDir();

        // por si la fuente es rara y no tiene descriptor
        float ascentFactor = 0.8f;
        float descentFactor = -0.2f;

        org.apache.pdfbox.pdmodel.font.PDFont font = pos.getFont();
        org.apache.pdfbox.pdmodel.font.PDFontDescriptor descriptor = font.getFontDescriptor();

        if (descriptor != null) {
            float asc = descriptor.getAscent();
            float desc = descriptor.getDescent();

            // Capas OCR/PDFs corruptos!
            if (asc != 0 || desc != 0) {
                ascentFactor = asc / 1000f;
                descentFactor = desc / 1000f;
            }
        }

        float ascent = fontSize * ascentFactor;
        float descent = fontSize * descentFactor;
        float calcH = ascent - descent;

        // Fallback
        if (calcH <= 0) {
            calcH = pos.getHeightDir();
            if (calcH <= 0) {
                calcH = 12f;
            }
            ascent = calcH * 0.8f;
            descent = calcH * -0.2f;
        }

        float rectX, rectY, rectW, rectH;

        if (angle == 0) {
            rectX = rawX;
            rectY = rawY + descent;
            rectW = width + paddingExtra;
            rectH = ascent - descent;
        } else if (angle == 90) {
            rectW = ascent - descent;
            rectH = width + paddingExtra;
            rectX = rawX - ascent;
            rectY = rawY;
        } else if (angle == 270) {
            rectW = ascent - descent;
            rectH = width + paddingExtra;
            rectX = rawX + descent;
            rectY = rawY - width - paddingExtra;
        } else if (angle == 180) {
            rectX = rawX - width - paddingExtra;
            rectY = rawY - ascent;
            rectW = width + paddingExtra;
            rectH = ascent - descent;            
        } else {
            rectX = rawX;
            rectY = rawY + descent;
            rectW = width + paddingExtra;
            rectH = ascent - descent;
        }

        return new float[] { rectX, rectY, rectW, rectH };
    }

    private void eliminarSuperposiciones(List<RedactionItemDTO> items) {
        int n = items.size();
        boolean[] eliminar = new boolean[n];

        for (int i = 0; i < n; i++) {
            RedactionItemDTO a = items.get(i);
            CoordinateDTO ca = a.coordinates();

            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                RedactionItemDTO b = items.get(j);
                if (!Objects.equals(a.page(), b.page())) continue;
                CoordinateDTO cb = b.coordinates();

                // Fracción del área de cada caja que cae dentro de la otra
                float ix = Math.max(0f, Math.min(ca.x() + ca.w(), cb.x() + cb.w()) - Math.max(ca.x(), cb.x()));
                float iy = Math.max(0f, Math.min(ca.y() + ca.h(), cb.y() + cb.h()) - Math.max(ca.y(), cb.y()));
                float inter = ix * iy;
                float areaA = ca.w() * ca.h();
                float areaB = cb.w() * cb.h();
                boolean contenida = areaA > 0 && inter / areaA >= 0.9f;
                if (!contenida) continue;

                boolean sonIguales = areaB > 0 && inter / areaB >= 0.9f;
                boolean quitarA;
                if (sonIguales) {
                    // Idénticas
                    quitarA = (b.action() == ActionStateDTO.WHITELISTED && a.action() != ActionStateDTO.WHITELISTED)
                            || (a.action() == b.action() && i > j);
                } else {
                    // A está dentro de B
                    quitarA = !(b.action() == ActionStateDTO.WHITELISTED && a.action() != ActionStateDTO.WHITELISTED);
                }

                if (quitarA) {
                    logSensible.debug("Superposición (pág. {}): se elimina [{}] {} {} por [{}] {} {}",
                            a.page(), a.text(), a.action(), ca, b.text(), b.action(), cb);
                    eliminar[i] = true;
                    break;
                }
            }
        }

        List<RedactionItemDTO> resultado = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!eliminar[i]) resultado.add(items.get(i));
        }
        items.clear();
        items.addAll(resultado);
    }

    /**
     * Inspecciona si la página contiene fuentes problemáticas (no incrustadas)
     */
    private boolean tieneFuenteProblematica(PDPage page) {
        PDResources resources = page.getResources();
        if (resources == null) {
            return false;
        }

        try {
            for (org.apache.pdfbox.cos.COSName fontName : resources.getFontNames()) {
                org.apache.pdfbox.pdmodel.font.PDFont font = resources.getFont(fontName);

                if (font != null) {
                    boolean noIncrustada = !font.isEmbedded();

                    boolean noEstandar = !font.isStandard14();

                    if (noIncrustada && noEstandar) {
                        logger.debug("Detectada fuente huérfana/no incrustada: {}", font.getName());
                        return true;
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Error al inspeccionar las fuentes de la página: {}", e.getMessage());
        }

        return false;
    }
}