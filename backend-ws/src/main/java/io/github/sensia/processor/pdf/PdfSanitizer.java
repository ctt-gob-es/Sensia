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

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.ws.ConfigManager;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Calendar;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class PdfSanitizer {

    private static final Logger logger = LoggerFactory.getLogger(PdfSanitizer.class);

    // Calidad JPEG de las páginas rasterizadas: con 0,90 no aparecen halos alrededor del texto
    private static final float JPG_QUALITY = 0.90f;

    // Resolución de cada página: la de su imagen más ancha (escaneo, fotografía), entre DPI_MIN y
    // DPI_MAX. Las páginas sin imágenes grandes (texto vectorial) usan processing.document.outputDpi
    private static final float DPI_MIN = 150f;
    private static final float DPI_MAX = 300f;
    private static final float DPI_SIN_IMAGENES = Math.max(DPI_MIN, Math.min(DPI_MAX,
            ConfigManager.getInstance("app.properties").getFloatConfig("processing.document.outputDpi", 200f)));
    // Ancho mínimo para que una imagen cuente como contenido principal de la página
    private static final int ANCHO_IMAGEN_GRANDE = 800;

    public static PDDocument sanitizarPdf(PDDocument doc) throws IOException {
        logger.info("Rasterizando páginas y eliminando metadatos");

        PDDocument safeDoc = new PDDocument();

        PDFRenderer renderer = new PDFRenderer(doc);
        int totalPages = doc.getNumberOfPages();

        for (int i = 0; i < totalPages; i++) {

            float dpi = dpiPagina(doc.getPage(i));
            BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
            float widthPt = image.getWidth() * 72f / dpi;
            float heightPt = image.getHeight() * 72f / dpi;
            PDRectangle originalSize = new PDRectangle(widthPt, heightPt);
            PDImageXObject pdImage = JPEGFactory.createFromImage(safeDoc, image, JPG_QUALITY);
            
            image.flush();

            PDPage newPage = new PDPage(originalSize);
            safeDoc.addPage(newPage);

            try (PDPageContentStream stream = new PDPageContentStream(safeDoc, newPage)) {
                stream.drawImage(pdImage, 0, 0, widthPt, heightPt);
            }
        }
        logger.info(IntStream.rangeClosed(1, totalPages)
                .mapToObj(i -> "pág_" + i)
                .collect(Collectors.joining(".. ")));

        limpiarMetadatosInternos(safeDoc);

        return safeDoc;
    }

    //
    // Resolución con la que se rasteriza la página: la de su imagen más ancha, si la tiene
    // (páginas escaneadas o fotografías), entre DPI_MIN y DPI_MAX; si no, DPI_SIN_IMAGENES.
    //
    private static float dpiPagina(PDPage page) {
        float dpi = 0;
        PDResources res = page.getResources();
        float anchoPulgadas = page.getCropBox().getWidth() / 72f;
        if (res != null && anchoPulgadas > 0) {
            for (COSName nombre : res.getXObjectNames()) {
                try {
                    if (res.getXObject(nombre) instanceof PDImageXObject img
                            && img.getWidth() >= ANCHO_IMAGEN_GRANDE) {
                        dpi = Math.max(dpi, img.getWidth() / anchoPulgadas);
                    }
                } catch (IOException e) {
                    logger.warn("No se ha podido leer una imagen al calcular la resolución de la página.");
                }
            }
        }
        return dpi > 0 ? Math.max(DPI_MIN, Math.min(DPI_MAX, dpi)) : DPI_SIN_IMAGENES;
    }


    private static void limpiarMetadatosInternos(PDDocument doc) {
        logger.info("   Eliminando metadatos del archivo generado...");
        
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        
        // Borrar XMP
        catalog.setMetadata(null);

        // Borrar Diccionario Info
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setAuthor(null);
        info.setCreator(null);
        info.setKeywords(null);
        info.setProducer(null);
        info.setSubject(null);
        info.setTitle(null);
        info.setTrapped(null);
        
        // Poner fechas genéricas o actuales
        Calendar now = Calendar.getInstance();
        info.setCreationDate(now);
        info.setModificationDate(now);

        // por seguridad limpiamos las estructuras por defecto.
        catalog.setAcroForm(null);
        catalog.setOpenAction(null);
    }
}