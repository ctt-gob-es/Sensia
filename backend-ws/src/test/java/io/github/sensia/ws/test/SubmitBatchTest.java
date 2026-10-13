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
import io.github.sensia.ws.generated.DocumentRedactionOptions;

import java.io.File;

public class SubmitBatchTest extends TestBase {

    @Test
    public void probarLoteCendoj() {
        File directorio = new File(RUTA_DATA, "dataset-cendoj");

        procesarDirectorio(directorio.getPath(), "SubmitBatch-01", operacionSubmitBatch(options -> {
            
            options.setRedactionPreview(true);
            
            DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
            docOpt.setRedactNames(true);
            options.setDocumentOptions(docOpt);
            
        }));
    }

    // @Test
    // public void probarLote1() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");

    //     procesarDirectorio(directorio.getPath(), "SubmitBatch-02", 2, 4, operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }


    // @Test
    // public void probarLote2() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-2");

    //     procesarDirectorio(directorio.getPath(), "SubmitBatch-03", 2, 4, operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }



    // @Test
    // public void probarFicheroTest1() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "doc5.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-05", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }


    // @Test
    // public void probarFicheroTest2() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "scanned_ocr.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-05", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }
    

    // @Test
    // public void probarFicheroTest3() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "doc1.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-05", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }

    // @Test
    // public void probarFicheroTest4() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "doc2.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-05", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }

    // @Test
    // public void probarFicheroTest5() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "doc3.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-05", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }

    //     @Test
    // public void probarFicheroTest6() {
    //     File directorio = new File(RUTA_DATA, "my-dataset-1");
    //     File fichero = new File(directorio, "doc4.pdf");
    //     procesarArchivo(fichero.getPath(), "SubmitBatch-04", operacionSubmitBatch(options -> {
            
    //         options.setRedactionPreview(true);
            
    //         DocumentRedactionOptions docOpt = new DocumentRedactionOptions();
    //         docOpt.setRedactNames(true);
    //         docOpt.setRedactQRCodes(true);
    //         options.setDocumentOptions(docOpt);
            
    //     }));
    // }


    
    
}