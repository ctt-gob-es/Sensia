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
import java.io.File;

import io.github.sensia.ws.core.TestBase;
import io.github.sensia.ws.generated.FaceMaskingOptions;

public class ImageLoadTest extends TestBase {

    @Test
    public void pruebaDeCargaImagenes() {
        File directorio = new File(RUTA_DATA, "dataset-wiki");
        int hilosConcurrentes = 4;
        
        procesarDirectorio(directorio.getPath(), "ImageLoadTest-01", hilosConcurrentes, operacionRedactImage(req -> {
            
            FaceMaskingOptions faces = new FaceMaskingOptions();
            faces.setMaskFaces(true);
            faces.setPixelationIntensity(80);
            req.setFaceOptions(faces);
            req.setRedactionPreview(true);
            
        }));
    }
}