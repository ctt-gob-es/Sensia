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

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.ws.ConfigManager;

public class TesseractInstanceFactory extends BasePooledObjectFactory<ITesseract> {
    
    private static final Logger logger = LoggerFactory.getLogger(TesseractInstanceFactory.class);
    private final String languaje;

    public TesseractInstanceFactory() {
         this.languaje = ConfigManager.getInstance("app.properties")
                 .getConfig("processing.ocr.lang", "spa").replace("\"", "").trim();
        
    }

    @Override
    public ITesseract create() {
        logger.debug("Creando nueva instancia nativa de Tesseract...");
        ITesseract tesseract = new Tesseract();
        tesseract.setDatapath(OcrPreprocessor.extractTessData());   // misma caché que el OCR de documentos
        tesseract.setLanguage(languaje);
        tesseract.setVariable("debug_file", "/dev/null");
        return tesseract;
    }


    @Override
    public PooledObject<ITesseract> wrap(ITesseract tesseract) {
        return new DefaultPooledObject<>(tesseract);
    }

    @Override
    public void passivateObject(PooledObject<ITesseract> p) {
      
        ITesseract tesseract = p.getObject();
        tesseract.setPageSegMode(3);
    }
}