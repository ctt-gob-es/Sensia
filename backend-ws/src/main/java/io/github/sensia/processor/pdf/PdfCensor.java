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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.processor.CensorResources;
import io.github.sensia.processor.ImageUtils;
import io.github.sensia.processor.NameDictionary;
import io.github.sensia.processor.PseudonymManager;
import io.github.sensia.processor.SensiaException;
import io.github.sensia.processor.img.FaceDetector;
import io.github.sensia.processor.model.DetectionType;
import io.github.sensia.processor.model.ProcessState;
import io.github.sensia.processor.model.RedactionResult;
import io.github.sensia.processor.model.TaggedPattern;
import io.github.sensia.processor.model.config.DocumentConfig;
import io.github.sensia.processor.model.config.DocumentInfo;
import io.github.sensia.processor.model.config.FaceMaskingConfig;
import io.github.sensia.processor.model.config.PdfProcessorContext;
import io.github.sensia.processor.model.config.YunetConfig;
import io.github.sensia.processor.model.dto.ActionStateDTO;
import io.github.sensia.processor.model.dto.CoordinateDTO;
import io.github.sensia.processor.model.dto.RedactionItemDTO;
import io.github.sensia.processor.ocr.OcrPreprocessor;

public class PdfCensor {

    private static final Logger logger = LoggerFactory.getLogger(PdfCensor.class);

    // CONFIGURACIÓN DE ZONA SEGURA (LOGO)
    // private static final boolean SALVAR_LOGO = true;
    // private static final float LOGO_X_MIN = 0;
    // private static final float LOGO_X_MAX = 200;
    // private static final float LOGO_Y_MIN = 700;
    // private static final float LOGO_Y_MAX = 900;

    // CONFIGURACIÓN DE ZONAS (cm)
    // private static final float POINTS_PER_CM = 28.3465f;
    // private static final float ANCHO_MARGEN_DERECHO_CM = 2.0f;
    // private static final float BARCODE_ALTO_CM = 2.5f;
    private static final PDType1Font helveticaBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    public static byte[] leerRecursoJava(String nombreArchivo) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(nombreArchivo)) {
            if (inputStream == null) {
                throw new IOException("Archivo no encontrado en resources: " + nombreArchivo);
            }
            return inputStream.readAllBytes();
        }
    }

    private static boolean necesitaOCR(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc);
            return text.trim().length() < 10;
        } catch (IOException e) {
            return true;
        }
    }

    public static List<RedactionItemDTO> aplicarCensura(DocumentInfo docInfo, CensorResources recursos,
            PdfProcessorContext context) throws IOException {

        DocumentConfig docConfig = context.documentConfig();
        List<String> whiteListWS = docConfig.allowedWords();
        List<String> blackListWS = docConfig.blockedWords();

        boolean redactNames = docConfig.redactNames();
        boolean redactNERNames = docConfig.redactNERNames();
        boolean redactNEROrg = docConfig.redactNEROrg();
        boolean redactNERLoc = docConfig.redactNERLoc();
        boolean redactNERMisc = docConfig.redactNERMisc();
        double redactNERScore = docConfig.redactNERScore();
        boolean redactionPreview = context.isPreview();
        int redactionThickness = docConfig.redactionThickness();
        boolean usarSeudonimos = docConfig.usePseudonyms();
        logger.warn("usePseudonyms: " + usarSeudonimos);
        PseudonymManager pseudoManager = new PseudonymManager(usarSeudonimos);

        File finalInput = docInfo.inputFile();
        List<TaggedPattern> patterns;
        List<String> whitelistString;
        NameDictionary nameDictionary;

        List<RedactionItemDTO> todasLasCensuras = new ArrayList<>();

        try {
            patterns = new ArrayList<>(recursos.getPatrones());
            whitelistString = new ArrayList<>(recursos.getListaBlanca());
            nameDictionary = recursos.getDiccionarioNombres();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (whiteListWS != null && !whiteListWS.isEmpty()) {
            whiteListWS.stream()
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .map(String::trim)
                    .forEach(whitelistString::add);
        }

        if (blackListWS != null && !blackListWS.isEmpty()) {
            String regexString = blackListWS.stream()
                    .filter(s -> s != null && !s.trim().isEmpty())
                    .map(s -> Pattern.quote(s.trim()).replace(" ", "\\E\\s+\\Q"))
                    .collect(Collectors.joining("|"));

            if (!regexString.isEmpty()) {
                patterns.add(new TaggedPattern("DATO_SENSIBLE", Pattern.compile(regexString, Pattern.CASE_INSENSITIVE)));
            }
        }
        List<Pattern> compiledWhitelistRegex = new ArrayList<>();
        List<String> literalWhitelist = new ArrayList<>();

        for (String item : whitelistString) {
            if (item.toLowerCase().startsWith("regex:")) {
                try {
                    compiledWhitelistRegex.add(Pattern.compile(item.substring(6).trim(), Pattern.CASE_INSENSITIVE));
                } catch (Exception e) {
                    logger.warn("No se pudo compilar la regex de whitelist: " + item, e);
                }
            } else {
                literalWhitelist.add(item.toLowerCase().trim());
            }
        }

        boolean esPdfEscaneado = necesitaOCR(docInfo.inputFile());
        if (esPdfEscaneado) {
            logger.warn("OCR activado...");
            int dpiDetectado = PdfDpiAnalyzer.detectarDpi(docInfo.inputFile());
            File ocrFile = OcrPreprocessor.convertirAPdfBuscable(docInfo.inputFile(), dpiDetectado);

            if (ocrFile == null || !ocrFile.exists()) {
                throw new SensiaException(ProcessState.ERROR,
                        "No se ha podido leer el texto del documento escaneado (OCR). Por seguridad no se devuelve el documento.");
            }
            finalInput = ocrFile;
        }

        List<RedactionItemDTO> cacheQR = new ArrayList<RedactionItemDTO>();

        try {
            try (PDDocument document = Loader.loadPDF(finalInput)) {
                int totalPages = document.getNumberOfPages();
                logger.info("Procesando {} páginas...", totalPages);

                for (int i = 0; i < totalPages; i++) {
                    PDPage page = document.getPage(i);
                    int currentPageNum = i + 1;

                    List<RedactionItemDTO> censurasDeEstaPagina = new ArrayList<>();

                    // imagenes
                    List<RedactionItemDTO> imgItemsFound = tratarImagenes(document, page, currentPageNum, docInfo,
                            context);
                    censurasDeEstaPagina.addAll(imgItemsFound);

                    // QR, barras, etc...
                    if (docConfig.redactQRCodes()) {
                        List<RedactionItemDTO> qrItemsFound = QrCensor.detectarQRsEnPagina(document, i, cacheQR,
                                docInfo);
                        censurasDeEstaPagina.addAll(qrItemsFound);
                    }
                    // Textos
                    PdfTextRedactionScanner finder = new PdfTextRedactionScanner(patterns, nameDictionary,
                            literalWhitelist, compiledWhitelistRegex, pseudoManager, recursos.getNerClient(),
                            redactNames, redactNERNames, redactNEROrg, redactNERLoc, redactNERMisc, redactNERScore, currentPageNum, docInfo.documentHash());
                    finder.getText(document);
                    List<RedactionItemDTO> textItemsFound = finder.getItemsFound();
                    censurasDeEstaPagina.addAll(textItemsFound);

                    // zonas fijas
                    List<RedactionItemDTO> fixedItemsFound = detectarZonasFijas(docInfo, currentPageNum,
                            context.documentConfig().fixedRedactionZones());
                    censurasDeEstaPagina.addAll(fixedItemsFound);
                    todasLasCensuras.addAll(censurasDeEstaPagina);

                    dibujarCensurasEnPagina(document, page, censurasDeEstaPagina, redactionThickness, redactionPreview);

                    logger.info("Pagina {}: {} elementos censurados.", (i + 1), censurasDeEstaPagina.size());
                }

                if (context.isPreview()){
                    logger.warn("Modo preview: se omite sanitizando.");
                    document.save(docInfo.censoredFile());
                }
                else{
                    logger.info("Sanitizando (Rasterizando) documento...");
                    try (PDDocument docSanitizadoRAM = PdfSanitizer.sanitizarPdf(document)) {
                        docSanitizadoRAM.save(docInfo.censoredFile());
                        logger.info("Documento sanitizado y guardado correctamente.");
                    } catch (Exception e) {
                        logger.error("Error al sanitizar el documento", e);
                        throw new RuntimeException("Fallo crítico al sanitizar el documento", e);
                    }
                }
                logger.info("--- FIN --- Guardado en: {}", docInfo.censoredFile().getAbsolutePath());

                return todasLasCensuras;

            } catch (IOException e) {
                logger.error("Error procesando el PDF", e);
                throw e;
            }
        } finally {
            if (finalInput != null && !finalInput.equals(docInfo.inputFile()) && finalInput.exists()) {
                finalInput.delete();
            }
            cacheQR.clear();
            cacheQR = null;
        }
    }

    /**
     * Aplica la censura directamente basándose en una lista de items ya revisados.
     * Omite la fase de detección (OCR, texto y QR). Solo se aplican las marcas activas (apply). En las
     * imágenes con un FACE activo se vuelve a dar el mismo cambiazo que en el análisis.
     */
    public static void aplicarCensuraDirecta(DocumentInfo docInfo, PdfProcessorContext context,
            List<RedactionItemDTO> itemsConfirmados) throws IOException {
        logger.info("Aplicando censura directa desde revisión (sin re-escaneo)...");

        try (PDDocument document = Loader.loadPDF(docInfo.inputFile())) {
            int totalPages = document.getNumberOfPages();

            for (int i = 0; i < totalPages; i++) {
                PDPage page = document.getPage(i);
                int currentPageNum = i + 1;
                page.setAnnotations(new ArrayList<>());

                // Imágenes con un FACE activo: mismo cambiazo en el diccionario que en el análisis.
                // Se localizan por su caja, porque en los escaneados los nombres de los XObject del
                // original no coinciden con los de la copia con OCR.
                List<RedactionItemDTO> rostrosActivos = itemsConfirmados.stream()
                        .filter(item -> item.page() == currentPageNum)
                        .filter(RedactionItemDTO::apply)
                        .filter(item -> item.detectionType() == DetectionType.FACE)
                        .filter(item -> item.action() == ActionStateDTO.REDACTED
                                     || item.action() == ActionStateDTO.REMOVED)
                        .collect(Collectors.toList());
                List<RedactionItemDTO> rostrosPixelados = new ArrayList<>();
                if (!rostrosActivos.isEmpty()) {
                    procesarStream(document, page, page, page.getResources(), new org.apache.pdfbox.util.Matrix(),
                            rostrosPixelados, currentPageNum, docInfo, context, rostrosActivos);
                }

                List<RedactionItemDTO> itemsA_Dibujar = itemsConfirmados.stream()
                        .filter(item -> item.page() == currentPageNum)
                        .filter(RedactionItemDTO::apply)
                        .filter(item -> item.action() == ActionStateDTO.REDACTED
                                     || item.action() == ActionStateDTO.REMOVED)
                        .map(item -> {
                            /*
                             * IMAGE: aquí no se quita la imagen del diccionario; se tapa entera
                             * transformando el tipo a FIXED_ZONE.
                             * FACE: la imagen ya está pixelada por el cambiazo. Si no ha podido darse
                             * (imagen no localizada o sin rostros en la nueva detección), se tapa la
                             * imagen entera para no dejar el rostro a la vista.
                             */
                            boolean rostroSinPixelar = item.detectionType() == DetectionType.FACE
                                    && !rostrosPixelados.contains(item);
                            if (rostroSinPixelar) {
                                logger.warn("No se ha podido pixelar la imagen con rostros de la página {}; se tapa entera.",
                                        currentPageNum);
                            }
                            if (item.detectionType() == DetectionType.IMAGE || rostroSinPixelar) {
                                return new RedactionItemDTO(
                                        DetectionType.FIXED_ZONE,
                                        "",
                                        ActionStateDTO.REDACTED,
                                        item.description(),
                                        item.text(),
                                        item.page(),
                                        item.coordinates(),
                                        item.sourceDocumentHash(),
                                        item.apply(), 
                                        item.pseudonym());
                            }
                            return item;
                        })
                        .collect(Collectors.toList());

                dibujarCensurasEnPagina(document, page, itemsA_Dibujar, context.documentConfig().redactionThickness(), context.isPreview());
                logger.info("Página {}: Dibujadas {} censuras definitivas.", currentPageNum, itemsA_Dibujar.size());
            }

            if (context.isPreview()){
                logger.warn("Modo preview. Omitiendo sanitizado.");
                document.save(docInfo.censoredFile());
            }
            else{
                
                logger.info("Sanitizando (Rasterizando) documento...");
                try (PDDocument docSanitizadoRAM = PdfSanitizer.sanitizarPdf(document)) {
                    docSanitizadoRAM.save(docInfo.censoredFile());
                    logger.info("Documento sanitizado y guardado correctamente.");
                } catch (Exception e) {
                    logger.error("Error al sanitizar el documento en censura directa", e);
                    throw new RuntimeException("Error al sanitizar el documento", e);
                }
            }
            logger.info("--- FIN --- Guardado en: {}", docInfo.censoredFile().getAbsolutePath());
        }
    }

    private static void dibujarCensurasEnPagina(PDDocument document, PDPage page,
            List<RedactionItemDTO> itemsA_Dibujar, int redactionThickness,
            boolean redactionPreview) throws IOException {

        if (itemsA_Dibujar == null || itemsA_Dibujar.isEmpty()) {
            logger.info("Sin items que censurar");
            return;
        }

        try (PDPageContentStream contentStream = new PDPageContentStream(document, page,
                PDPageContentStream.AppendMode.APPEND, true, true)) {

            for (RedactionItemDTO item : itemsA_Dibujar) {
                CoordinateDTO coord = item.coordinates();
                DetectionType tipo = item.detectionType();
                ActionStateDTO action = item.action();

                if (tipo == DetectionType.FACE) {
                    continue;
                }

                // La imagen completa con rostros se propone desactivada pero no se pinta
                if (tipo == DetectionType.IMAGE && !item.apply()) {
                    continue;
                }

                logger.info("Dibujando censura ... {} {}", tipo, coord);

                    float padX = 0;
                    float padY = 0;
                    float padW = 0;
                    float padH = 0;

                    if (tipo == DetectionType.QR_CODE) {
                        padX = -2f;
                        padY = -2f;
                        padW = 2f;
                        padH = 2f;
                    } else {
                        float textPadding = coord.h() * 0.05f * redactionThickness;
                        padX = -2f;
                        padY = -textPadding;
                        padW = 4f;
                        padH = textPadding * 2.5f;
                    }


                if (redactionPreview) {
                    // Modo preview
                    Color color = (action == ActionStateDTO.REDACTED ? Color.RED : Color.GREEN);
                    boolean cruz = (action == ActionStateDTO.REDACTED);
                    contentStream.setStrokingColor(color);
                    contentStream.setLineWidth(1.5f);

                    if (tipo == DetectionType.IMAGE) {
                        float startX = coord.x();
                        float startY = coord.y();
                        float endX = coord.x() + coord.w();
                        float endY = coord.y() + coord.h();

                        if (cruz) {
                            contentStream.moveTo(startX, startY);
                            contentStream.lineTo(endX, endY);

                            contentStream.moveTo(startX, endY);
                            contentStream.lineTo(endX, startY);
                        } else {
                            contentStream.addRect(coord.x(), coord.y(), coord.w(), coord.h());
                        }

                        contentStream.stroke();
                    } else {

                        contentStream.addRect(coord.x() + padX, coord.y() + padY, coord.w() + padW, coord.h() + padH);
                        contentStream.stroke();
                    }

                } else {
                    // Modo censura real
                    if (tipo == DetectionType.IMAGE) {
                        // La imagen ya se borró del diccionario del PDF
                        continue;
                    }

                    if (action == ActionStateDTO.WHITELISTED || action == ActionStateDTO.EXCLUDED_ZONE) {
                        // Palabras y zonas permitidas
                        continue;
                    }

                    if (tipo == DetectionType.FIXED_ZONE) {
                        contentStream.setNonStrokingColor(Color.WHITE);
                    } else {
                        contentStream.setNonStrokingColor(Color.BLACK);
                    }

                    if (item.pseudonym() != null && !item.pseudonym().isEmpty() && tipo != DetectionType.FIXED_ZONE) {
                        dibujarSinonimo(contentStream, coord, item, padX, padY, padW, padH);
                    } 
                    else{
                        contentStream.addRect(
                                coord.x() + padX,
                                coord.y() + padY,
                                coord.w() + padW,
                                coord.h() + padH);
                        contentStream.fill();
                    }
                }
            }
        }
    }

    private static void dibujarSinonimo(PDPageContentStream contentStream, CoordinateDTO coord, RedactionItemDTO item,
            float padX, float padY, float padW, float padH) throws IOException {       

            // Dibujamos un rectángulo blanco para tapar la palabra original. Mismos margenes que la censura en negro
            contentStream.setNonStrokingColor(java.awt.Color.WHITE);
            contentStream.addRect(coord.x() + padX, coord.y() + padY, coord.w() + padW, coord.h() + padH);            
            contentStream.fill();

            // Escribimos el seudónimo encima en negro
            contentStream.setNonStrokingColor(java.awt.Color.BLACK);
            contentStream.beginText();

            float fontSize = coord.h() * 0.8f;

            contentStream.setFont(helveticaBold, fontSize);

            contentStream.newLineAtOffset(coord.x(), coord.y() + (coord.h() * 0.15f));
            contentStream.showText(item.pseudonym());
            contentStream.endText();
    }

    private static List<RedactionItemDTO> tratarImagenes(PDDocument doc, PDPage page, int currentPage,
            DocumentInfo docInfo, PdfProcessorContext context)
            throws IOException {
        page.setAnnotations(new ArrayList<>());
        List<RedactionItemDTO> modificacionesImagenes = new ArrayList<>();
        procesarStream(doc, page, page, page.getResources(), new org.apache.pdfbox.util.Matrix(),
                modificacionesImagenes, currentPage, docInfo, context, null);
        return modificacionesImagenes;
    }

    /**
     * Recorre el flujo y trata sus imágenes.
     * Con rostrosConfirmados a null (análisis), detecta rostros, elimina imágenes y genera las marcas
     * en modificacionesImagenes. Con rostrosConfirmados (confirmación), solo da el cambiazo en las
     * imágenes cuya caja coincide con un FACE activo, no elimina nada y deja en modificacionesImagenes
     * los FACE cuya imagen ha quedado pixelada.
     */
    private static void procesarStream(PDDocument doc, PDPage page, PDContentStream contentStream,
            PDResources resources, org.apache.pdfbox.util.Matrix ctmInicial,
            List<RedactionItemDTO> modificacionesImagenes, int currentPage, DocumentInfo docInfo,
            PdfProcessorContext context, List<RedactionItemDTO> rostrosConfirmados)
            throws IOException {

        PDFStreamParser parser = new PDFStreamParser(contentStream);
        List<Object> tokens = new ArrayList<>();
        Object tokenObj;
        while ((tokenObj = parser.parseNextToken()) != null) {
            tokens.add(tokenObj);
        }
        // Imágenes en línea (BI ... ID ... EI): se convierten en XObject para que pasen por el
        // mismo tratamiento que el resto (rostros, eliminación y zonas protegidas)
        tokens = convertirImagenesEnLinea(doc, resources, tokens);

        PDRectangle cropBox = page.getCropBox();

        // Las imágenes se dibujan en el espacio de la página sin girar, el mismo del CropBox:
        // no se intercambian ancho y alto en las páginas con /Rotate
        float pageWidth = cropBox.getWidth();
        float pageHeight = cropBox.getHeight();

        List<Object> newTokens = new ArrayList<>();
        Stack<org.apache.pdfbox.util.Matrix> graphicsStack = new Stack<>();
        // En un formulario se parte de la matriz con la que se dibuja, no de la identidad
        org.apache.pdfbox.util.Matrix currentMatrix = ctmInicial.clone();
        List<Float> argsBuffer = new ArrayList<>();

        for (Object token : tokens) {
            if (token instanceof org.apache.pdfbox.cos.COSNumber) {
                argsBuffer.add(((org.apache.pdfbox.cos.COSNumber) token).floatValue());
                newTokens.add(token);
            } else if (token instanceof Operator) {
                Operator op = (Operator) token;
                String opName = op.getName();

                if (opName.equals("q")) {
                    graphicsStack.push(currentMatrix.clone());
                    argsBuffer.clear();
                } else if (opName.equals("Q")) {
                    if (!graphicsStack.isEmpty())
                        currentMatrix = graphicsStack.pop();
                    argsBuffer.clear();
                } else if (opName.equals("cm")) {
                    if (argsBuffer.size() >= 6) {
                        float a = argsBuffer.get(argsBuffer.size() - 6);
                        float b = argsBuffer.get(argsBuffer.size() - 5);
                        float c = argsBuffer.get(argsBuffer.size() - 4);
                        float d = argsBuffer.get(argsBuffer.size() - 3);
                        float e = argsBuffer.get(argsBuffer.size() - 2);
                        float f = argsBuffer.get(argsBuffer.size() - 1);
                        currentMatrix.concatenate(new org.apache.pdfbox.util.Matrix(a, b, c, d, e, f));
                    }
                    argsBuffer.clear();
                }

                if (opName.equals("Do")) {
                    argsBuffer.clear();
                    boolean eliminarToken = false;

                    if (!newTokens.isEmpty() && newTokens.get(newTokens.size() - 1) instanceof COSName) {
                        COSName xObjectName = (COSName) newTokens.get(newTokens.size() - 1);
                        PDXObject xObject = resources.getXObject(xObjectName);

                        if (xObject instanceof PDImageXObject) {
                            // Caja a partir de la matriz completa: getScaleX/Y da 0 en imágenes giradas 90°
                            CoordinateDTO caja = cajaImagen(currentMatrix);
                            float imgX = caja.x();
                            float imgY = caja.y();
                            float imgW = caja.w();
                            float imgH = caja.h();

                            if (rostrosConfirmados != null) {
                                // Cambiazo como en el análisis, solo en las imágenes con un FACE activo. No se elimina ninguna imagen.
                                List<RedactionItemDTO> coincidentes = rostrosConfirmados.stream()
                                        .filter(rostro -> rostro.coordinates() != null
                                                && mismaCaja(rostro.coordinates(), caja))
                                        .collect(Collectors.toList());

                                if (!coincidentes.isEmpty() && context.faceConfig() != null) {
                                    BufferedImage imagenOfuscada = detectarConYunet(((PDImageXObject) xObject),
                                            context.yunetConfig(), context.faceConfig(), context.isPreview());
                                    if (imagenOfuscada != null) {
                                        resources.put(xObjectName, org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
                                                .createFromImage(doc, imagenOfuscada, 0.95f));
                                        logger.info("Caras pixeladas en la confirmación: " + xObjectName.getName());
                                        modificacionesImagenes.addAll(coincidentes);
                                    } else {
                                        logger.warn("Sin rostros en la nueva detección de la imagen: "
                                                + xObjectName.getName());
                                    }
                                }
                            } else {
                                // Solo se buscan rostros si la petición lo pide (maskFaces)
                                boolean buscarRostros = context.faceConfig() != null && context.faceConfig().maskFaces();
                                BufferedImage imagenOfuscada = buscarRostros
                                        ? detectarConYunet(((PDImageXObject) xObject), context.yunetConfig(),
                                                context.faceConfig(), context.isPreview())
                                        : null;

                                if (imagenOfuscada != null) {
                                    // Hay caras: damos el cambiazo en el diccionario (No eliminamos el token),
                                    // aunque se haya pedido removeImages
                                    resources.put(xObjectName, org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
                                            .createFromImage(doc, imagenOfuscada, 0.95f));
                                    eliminarToken = false;
                                    logger.info("Caras encontradas: " + xObjectName.getName());
                                    modificacionesImagenes.add(new RedactionItemDTO(
                                            DetectionType.FACE,
                                            "",
                                            ActionStateDTO.REDACTED,
                                            "Rostros ofuscados en imagen",
                                            xObjectName.getName(),
                                            currentPage,
                                            new CoordinateDTO(imgX, imgY, imgW, imgH), 
                                            docInfo.documentHash(), 
                                            true, 
                                            ""));
                                    // La imagen completa, desactivada: el revisor puede activarla para quitarla
                                    modificacionesImagenes.add(new RedactionItemDTO(
                                            DetectionType.IMAGE,
                                            "",
                                            ActionStateDTO.REMOVED,
                                            "Imagen completa con rostros",
                                            xObjectName.getName(),
                                            currentPage,
                                            new CoordinateDTO(imgX, imgY, imgW, imgH),
                                            docInfo.documentHash(),
                                            false,
                                            ""));
                                } else {
                                    // No hay caras: fondos, páginas escaneadas y zonas de exclusión se respetan;
                                    // en otro caso se borra la imagen si así lo han indicado
                                    boolean esZonaProtegida = !debeEliminarImagen(imgX, imgY, imgW, imgH, pageWidth,
                                            pageHeight, context.documentConfig().imageExclusionZones());

                                    if (esZonaProtegida) {
                                        logger.info("Imagen en zona protegida: " + xObjectName.getName());
                                        modificacionesImagenes.add(new RedactionItemDTO(
                                                DetectionType.IMAGE,
                                                "",
                                                ActionStateDTO.EXCLUDED_ZONE,
                                                "Imagen protegida en PDF",
                                                xObjectName.getName(),
                                                currentPage,
                                                new CoordinateDTO(imgX, imgY, imgW, imgH),
                                                docInfo.documentHash(),
                                                true,
                                                ""));
                                    } else if (context.documentConfig().removeImages()) {
                                        eliminarToken = true;
                                        logger.info("Quitando imagen: " + xObjectName.getName());
                                        logger.info(String.format("IMAGEN ELIMINADA (X=%.2f, Y=%.2f)", imgX, imgY));
                                        modificacionesImagenes.add(new RedactionItemDTO(
                                                DetectionType.IMAGE,
                                                "",
                                                ActionStateDTO.REMOVED,
                                                "Imagen eliminada del PDF",
                                                xObjectName.getName(),
                                                currentPage,
                                                new CoordinateDTO(imgX, imgY, imgW, imgH),
                                                docInfo.documentHash(),
                                                true,
                                                ""));
                                    }
                                }
                            }

                        } else if (xObject instanceof PDFormXObject) {
                            logger.info("   Entrando en Form XObject: " + xObjectName.getName());
                            PDFormXObject form = (PDFormXObject) xObject;
                            // Matriz del formulario sobre la actual, como al dibujarlo (PDFStreamEngine),
                            // para que las cajas de sus imágenes queden en el espacio de la página
                            org.apache.pdfbox.util.Matrix ctmForm = currentMatrix.clone();
                            ctmForm.concatenate(form.getMatrix());
                            // Sin recursos propios, el formulario usa los del flujo que lo dibuja
                            PDResources recursosForm = form.getResources() != null ? form.getResources() : resources;
                            procesarStream(doc, page, form, recursosForm, ctmForm, modificacionesImagenes,
                                    currentPage, docInfo, context, rostrosConfirmados);
                        }
                    }

                    if (eliminarToken) {
                        newTokens.remove(newTokens.size() - 1);
                    } else {
                        newTokens.add(token);
                    }
                } else {
                    newTokens.add(token);
                    if (!opName.equals("cm") && !opName.equals("q") && !opName.equals("Q")) {
                        argsBuffer.clear();
                    }
                }
            } else {
                newTokens.add(token);
            }
        }

        PDStream newStream = new PDStream(doc);

        try (OutputStream output = newStream.createOutputStream(org.apache.pdfbox.cos.COSName.FLATE_DECODE)) {
            ContentStreamWriter writer = new ContentStreamWriter(output);
            writer.writeTokens(newTokens);
        }

        if (contentStream instanceof PDPage) {
            ((PDPage) contentStream).setContents(newStream);
        } else if (contentStream instanceof PDFormXObject) {
            PDFormXObject form = (PDFormXObject) contentStream;
            form.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.LENGTH, null);

            try (OutputStream os = form.getStream().createOutputStream();
                    InputStream is = newStream.createInputStream()) {
                is.transferTo(os);
            }
        }
    }

    private static BufferedImage detectarConYunet(PDImageXObject pdImage, YunetConfig yunetOpts,
            FaceMaskingConfig faceOpts, boolean redactionPreview) {

        byte[] image;
        try {
            image = ImageUtils.extractImageBytes(pdImage);
        } catch (Exception e) {
            logger.warn("Error: al extraer la imagen", e);
            // Sin la imagen no se pueden buscar rostros: el documento no puede darse por anonimizado
            logger.error("No se ha podido extraer una imagen del PDF para buscar rostros.", e);
            throw new RuntimeException("No se ha podido analizar una imagen del documento en busca de rostros.", e);
        }

        FaceDetector detector = FaceDetector.getFaceDetector();
        if (detector == null) {
            logger.error("Error: FaceDetectorYN no está inicializado.");
            throw new RuntimeException("Error: FaceDetectorYN no está inicializado.");
        }

        try (RedactionResult detection = detector.detectFaces(image, yunetOpts, faceOpts, redactionPreview)) {
            if (detection.getRedactionItems().size() > 0) {
                return ImageUtils.bytesToBufferedImage(detection.getImageData());
            }
            return null;
        } catch (Exception e) {
            logger.error("Error: durante la ejecucion de FaceDetectorYN.", e);
            throw new RuntimeException("Error: durante la ejecucion de FaceDetectorYN.", e);
        }

    }

    /**
     * Sustituye cada imagen en línea por un XObject equivalente ("/ImN Do"). Se dibuja en la misma
     * posición, porque las dos ocupan el cuadrado unidad de la matriz actual. Las máscaras
     * (ImageMask) no son fotografías y se dejan como están. Si una imagen no puede leerse, se
     * propaga el error: el documento no puede darse por anonimizado.
     */
    private static List<Object> convertirImagenesEnLinea(PDDocument doc, PDResources resources,
            List<Object> tokens) throws IOException {
        List<Object> resultado = new ArrayList<>(tokens.size());
        for (Object token : tokens) {
            if (token instanceof Operator op && "BI".equals(op.getName())) {
                org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage enLinea =
                        new org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage(
                                op.getImageParameters(), op.getImageData(), resources);
                if (!enLinea.isStencil()) {
                    BufferedImage imagen = enLinea.getImage();
                    if (imagen == null) {
                        throw new IOException("No se ha podido leer una imagen en línea del documento.");
                    }
                    PDImageXObject comoXObject = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                            .createFromImage(doc, imagen);
                    resultado.add(resources.add(comoXObject));
                    resultado.add(Operator.getOperator("Do"));
                    continue;
                }
            }
            resultado.add(token);
        }
        return resultado;
    }

    /**
     * Caja, en el espacio de la página, del cuadrado unidad en el que se dibuja una imagen con esta
     * matriz. Cubre también las imágenes giradas, inclinadas o reflejadas.
     */
    private static CoordinateDTO cajaImagen(org.apache.pdfbox.util.Matrix ctm) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (float[] esquina : new float[][] { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } }) {
            java.awt.geom.Point2D.Float p = ctm.transformPoint(esquina[0], esquina[1]);
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        return new CoordinateDTO(minX, minY, maxX - minX, maxY - minY);
    }

    /**
     * Indica si la caja de una marca corresponde a la de una imagen, con un margen de un punto
     * para absorber redondeos entre el análisis y la confirmación.
     */
    private static boolean mismaCaja(CoordinateDTO marca, CoordinateDTO imagen) {
        final float tolerancia = 1f;
        return Math.abs(marca.x() - imagen.x()) <= tolerancia
                && Math.abs(marca.y() - imagen.y()) <= tolerancia
                && Math.abs(marca.w() - imagen.w()) <= tolerancia
                && Math.abs(marca.h() - imagen.h()) <= tolerancia;
    }

    private static boolean debeEliminarImagen(float imgX, float imgY, float imgW, float imgH, float pageWidth,
            float pageHeight,
            List<CoordinateDTO> zonasProtegidas) {
        boolean esImagenProtegida = false;
        if (imgW > (pageWidth * 0.85f) && imgH > (pageHeight * 0.85f)) {
            // Fondos y páginas escaneadas no se eliminan (RF-IMG-03)
            return false;
        }

        if (zonasProtegidas != null && !zonasProtegidas.isEmpty()) {
            for (CoordinateDTO zona : zonasProtegidas) {

                float zonaMinX = zona.x();
                float zonaMaxX = zona.x() + zona.w();
                float zonaMinY = zona.y();
                float zonaMaxY = zona.y() + zona.h();

                // La imagen se solapa con una zona protegida
                boolean seSolapan = !(imgX > zonaMaxX ||
                        imgX + imgW < zonaMinX ||
                        imgY > zonaMaxY ||
                        imgY + imgH < zonaMinY);

                if (seSolapan) {
                    esImagenProtegida = true;
                    break;
                }
            }
        }

        if (esImagenProtegida) {
            logger.info(String.format("IMAGEN/LOGO SALVADO en zona protegida (X=%.2f, Y=%.2f)", imgX, imgY));
            return false;
        } else {
            return true;
        }
    }

    private static List<RedactionItemDTO> detectarZonasFijas(DocumentInfo docInfo, int currentPage,
            List<CoordinateDTO> fixedRedactionZones) {
        List<RedactionItemDTO> zonasEncontradas = new ArrayList<>();
        logger.info("Censuras fijas... {}", fixedRedactionZones == null ? 0 : fixedRedactionZones.size());

        if (fixedRedactionZones != null && !fixedRedactionZones.isEmpty()) {
            for (CoordinateDTO rect : fixedRedactionZones) {
                RedactionItemDTO item = new RedactionItemDTO(
                        DetectionType.FIXED_ZONE,
                        "",
                        ActionStateDTO.REDACTED,
                        "Zona de censura fija",
                        "",
                        currentPage,
                        rect, 
                        docInfo.documentHash(), 
                        true, 
                        "");
                zonasEncontradas.add(item);
                logger.info("Incluyendo censura fija... {}", rect);

            }
        }
        return zonasEncontradas;
    }

}