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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class PdfDpiAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(PdfDpiAnalyzer.class);

    private static final int DPI_DEFAULT = 300;

    /**
     * Analiza el PDF y devuelve el DPI predominante de sus imágenes.
     * Si la calidad es muy baja (< 200), devuelve 300 para proteger el OCR.
     */
    public static int detectarDpi(File pdfFile) {
        logger.info("Analizando DPI del documento original...");
        
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            Map<Integer, Integer> frecuenciaDpi = new HashMap<>();
            int paginasAAnalizar = Math.min(doc.getNumberOfPages(), 5); // Muestreo de las primeras 5

            for (int i = 0; i < paginasAAnalizar; i++) {
                PDPage page = doc.getPage(i);
                PDResources res = page.getResources();
                
                if (res == null) continue;
                
                float anchoPaginaInch = page.getMediaBox().getWidth() / 72f;

                for (COSName xName : res.getXObjectNames()) {
                    PDXObject xObj = res.getXObject(xName);
                    if (xObj instanceof PDImageXObject) {
                        PDImageXObject img = (PDImageXObject) xObj;
                        
                        // Ignorar imágenes pequeñas (iconos, firmas, logos)
                        if (img.getWidth() < 800) continue; 

                        //  DPI = píxeles / pulgadas
                        int dpiCalculado = Math.round(img.getWidth() / anchoPaginaInch);
                        
                        // Redondeamos (ej: 298 -> 300)
                        int dpiRedondeado = (int) (Math.round(dpiCalculado / 50.0) * 50);
                        
                        frecuenciaDpi.put(dpiRedondeado, frecuenciaDpi.getOrDefault(dpiRedondeado, 0) + 1);
                    }
                }
            }

            if (frecuenciaDpi.isEmpty()) {
                logger.warn("   No se detectaron imágenes grandes. Usando Default: {}", DPI_DEFAULT);
                return DPI_DEFAULT;
            }

            // DPI más frecuente (moda)
            int dpiGanador = DPI_DEFAULT;
            int maxFrecuencia = -1;

            for (Map.Entry<Integer, Integer> entry : frecuenciaDpi.entrySet()) {
                if (entry.getValue() > maxFrecuencia) {
                    maxFrecuencia = entry.getValue();
                    dpiGanador = entry.getKey();
                }
            }

            logger.info("   DPI Predominante detectado: {}", dpiGanador);

            // A 72 o 96 ppp Tesseract no reconoce bien el texto: se fuerza 200/300 para reescalar
            if (dpiGanador < 200) {
                logger.warn("   El DPI detectado ({}) es muy bajo para OCR fiable. Forzando 300.", dpiGanador);
                return 300;
            }

            return Math.min(dpiGanador, 300);

        } catch (IOException e) {
            logger.error("Error analizando DPI", e);
            return DPI_DEFAULT;
        }
    }
}