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

package io.github.sensia.processor.ocr;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.github.sensia.ws.ConfigManager;

public class OcrPreprocessor {

    private static final Logger logger = LoggerFactory.getLogger(OcrPreprocessor.class);

        private static final String CACHE_DIR_NAME = "io.github.sensia.tessdata-v1";

    private static final String IDIOMAS = ConfigManager.getInstance("app.properties")
            .getConfig("processing.ocr.lang", "spa").replace("\"", "").trim();

    private static final String MODELO_ORIENTACION = "osd";

    // Fuente invisible con la que Tesseract escribe la capa de texto del PDF
    private static final String FUENTE_PDF = "pdf.ttf";

    /**
     * Copia a una carpeta temporal los ficheros que falten: los idiomas de
     * processing.ocr.lang, osd y pdf.ttf. Tesseract es nativo y no puede leerlos dentro del JAR.
     * Se comprueba fichero a fichero en cada llamada: así, si se añade un modelo nuevo
     * o la limpieza de temporales borra la caché, se vuelve a copiar sin reiniciar.
     */
    public static synchronized String extractTessData() {
        try {
            File cacheDir = new File(System.getProperty("java.io.tmpdir"), CACHE_DIR_NAME);
            if (!cacheDir.exists() && !cacheDir.mkdirs() && !cacheDir.exists()) {
                throw new IOException("No se pudo crear el directorio de caché: " + cacheDir.getAbsolutePath());
            }

            List<String> modelos = new ArrayList<>();
            for (String idioma : IDIOMAS.split("\\+")) {
                if (!idioma.isBlank()) {
                    modelos.add(idioma.trim() + ".traineddata");
                }
            }

            modelos.add(MODELO_ORIENTACION + ".traineddata");
            modelos.add(FUENTE_PDF);
 

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            for (String modelo : modelos) {
                File destino = new File(cacheDir, modelo);
                if (destino.isFile() && destino.length() > 0) {
                    continue;
                }
                String recurso = "tessdata/" + modelo;
                try (InputStream is = classLoader.getResourceAsStream(recurso)) {
                    if (is == null) {
                        throw new IllegalStateException("No se encuentra " + recurso
                                + " en el JAR (processing.ocr.lang=" + IDIOMAS + ")");
                    }
                    // Se copia a un temporal y se renombra: si la copia se interrumpe,
                    // no queda un modelo a medias que parezca válido
                    File parcial = new File(cacheDir, modelo + ".part");
                    Files.copy(is, parcial.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Files.move(parcial.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Extraído {} a la caché de OCR", recurso);
                }
            }
            return cacheDir.getAbsolutePath();

        } catch (IOException e) {
            throw new RuntimeException("Error gestionando recursos de OCR", e);
        }
    }

    public static File convertirAPdfBuscable(File inputPdf, int dpiObjetivo) {
        logger.info("Iniciando OCR Sincronizado para: " + inputPdf.getName());
        logger.info("Iniciando OCR con DPI objetivo: " + dpiObjetivo);
        ITesseract instance = new Tesseract();
        instance.setDatapath(extractTessData());
        instance.setLanguage(IDIOMAS);

        // Evita que el PDF salga gigante.
        instance.setVariable("user_defined_dpi", String.valueOf(dpiObjetivo));
        instance.setVariable("textonly_pdf", "0");
        instance.setVariable("preserve_interword_spaces", "1");
        // Page Segmentation Mode 1: Detección automática de orientación y segmentación de página.
        instance.setPageSegMode(1);

        List<ITesseract.RenderedFormat> formats = new ArrayList<>();
        formats.add(ITesseract.RenderedFormat.PDF);

        String outputBase = inputPdf.getAbsolutePath().replace(".pdf", "_ocr");
        File resultFile = new File(outputBase + ".pdf");
        
        List<File> imageFiles = new ArrayList<>();
        File textFileList = null;
        boolean valido = false;
        try (PDDocument document = Loader.loadPDF(inputPdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int totalPages = document.getNumberOfPages();
            
            logger.info("Documento origen tiene {} páginas. Renderizando imágenes...", totalPages);

            for (int i = 0; i < totalPages; i++) {
    
                BufferedImage image = renderer.renderImageWithDPI(i, dpiObjetivo, ImageType.RGB);
                File tempImg = File.createTempFile("page_" + i + "_", ".png");
                ImageIO.write(image, "png", tempImg);
                image.flush();
                imageFiles.add(tempImg);
            }

            logger.info("⏳ Preparando manifiesto para OCR multipágina nativo...");
            
            textFileList = File.createTempFile("tess_input_list_", ".txt");
            try (java.io.PrintWriter pw = new java.io.PrintWriter(textFileList)) {
                for (File img : imageFiles) {
                    pw.println(img.getAbsolutePath());
                }
            }

            logger.info("⏳ Ejecutando motor OCR (Tesseract gestionará la fusión nativa)...");

            instance.createDocuments(
                    new String[] { textFileList.getAbsolutePath() },
                    new String[] { outputBase },
                    formats);

            resultFile = new File(outputBase + ".pdf");

            // Validación final
            if (resultFile.exists() && isPdfValid(resultFile)) {
                logger.info("PDF con capa de texto OCR generado: " + resultFile.getAbsolutePath());
                valido = true;
                return resultFile;
            } else {
                logger.error("El proceso OCR finalizó, pero el archivo PDF resultó corrupto.");
                return null;
            }

        } catch (IOException | TesseractException | RuntimeException e) {
            logger.error("Error durante el procesamiento OCR del archivo: " + inputPdf.getName(), e);
        } finally {
            for (File f : imageFiles) {
                if (f != null && f.exists()) f.delete();
            }
            if (textFileList != null) textFileList.delete();
            if (!valido) resultFile.delete();
        }
        return null;
    }

    private static boolean isPdfValid(File pdfFile) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try (PDDocument ignored = Loader.loadPDF(pdfFile)) {
                return true; 
            } catch (IOException e) {
                try {
                    TimeUnit.MILLISECONDS.sleep(150);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return false;
    }
}
