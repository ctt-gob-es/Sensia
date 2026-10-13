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
package io.github.sensia.ws.test;

import org.junit.Test;
import io.github.sensia.ws.core.TestBase;
import java.io.File;

public class ExtractImagesTest extends TestBase {

    @Test
    public void probarExtraccion() {
        File fichero = new File(new File(RUTA_DATA, "dataset-pdfs"), "documento_1.pdf");
        
        procesarArchivo(fichero.getPath(), "ExtractImagesTest-01", operacionExtractImages(req -> {
            req.setMinHeight(100);
            req.setMinWidth(100);
        }));
    }

    @Test
    public void probarExtraccionConExlusion() {
        File fichero = new File(new File(RUTA_DATA, "dataset-pdfs"), "documento_2.pdf");
        
        procesarArchivo(fichero.getPath(), "ExtractImagesTest-02", operacionExtractImages(req -> {
            req.setMinHeight(101);
            req.setMinWidth(100);
        }));
    }
}
