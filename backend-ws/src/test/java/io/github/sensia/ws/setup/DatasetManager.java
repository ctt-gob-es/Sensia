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

package io.github.sensia.ws.setup;

import java.io.*;
import org.junit.Test;

import io.github.sensia.ws.core.TestBase;
import io.github.sensia.ws.core.TestUtil;

public class DatasetManager {

 static {
        try (InputStream is = TestBase.class.getClassLoader().getResourceAsStream("test.properties")) {
            if (is != null) {
                System.getProperties().load(is);
            }
        } catch (IOException e) {
            System.err.println("No se pudo cargar test.properties, usando valores por defecto.");
            throw new RuntimeException(e);
        }
    }

    protected static final String RUTA_DATA = System.getProperty("test.data.dir", "./test-data/");


    public void prepararTodosLosDatasets() {
        System.out.println("\n==================================================");
        System.out.println("  INICIALIZANDO DATASETS DE PRUEBA");
        System.out.println("==================================================");
        prepararDatasetWiki();
        prepararDatasetCendoj();
        generarPdfsDesdePlantilla();
    }
    
    @Test
    public void consultarWikipedia() {
        File directorio = new File(RUTA_DATA, "dataset-wiki");

        if (!directorio.exists()) {
            directorio.mkdirs();
        }

        File ficheroOuput = new File(directorio, "urls_wiki.txt");
        TestUtil.consultarWipedia("pedestrians city", 10, ficheroOuput.getPath());
    }

    @Test
    public void prepararDatasetWiki() {
        File directorio = new File(RUTA_DATA, "dataset-wiki");

        if (!directorio.exists()) {
            directorio.mkdirs();
        }
        String ficheroUrls = TestUtil.getPathResource ("urls_wiki.txt");
        TestUtil.descargarWikipedia(ficheroUrls, directorio.getPath(), "peatones");
    }

    @Test
    public void prepararDatasetCendoj() {
        String ficheroUrls = TestUtil.getPathResource ("urls_cendoj.txt");

        File directorio = new File(RUTA_DATA, "dataset-cendoj");

        if (!directorio.exists()) {
            directorio.mkdirs();
        }

        System.out.println("\n==================================================");
        System.out.println("  PREPARANDO DATASET: LEYENDO FICHERO EXTERNO");
        System.out.println("==================================================");

        try {

            java.util.List<String> urlsCendoj = TestUtil.leerUrlsDesdeFichero(ficheroUrls);

            if (urlsCendoj.isEmpty()) {
                org.junit.Assert
                        .fail("❌ El fichero " + ficheroUrls + " está vacío o no tiene URLs válidas (sin comentar).");
            }

            System.out.println("[INFO] Se han encontrado " + urlsCendoj.size() + " URLs en el fichero.");

            TestUtil.prepararDirectorioDataset(directorio, urlsCendoj.size(), () -> {
                TestUtil.descargarListaPdfs(urlsCendoj, directorio.getPath());
            });

            File[] archivos = directorio.listFiles((d, name) -> name.endsWith(".pdf"));
            org.junit.Assert.assertTrue("❌ El directorio está vacío. Hubo un fallo en la descarga.",
                    archivos != null && archivos.length > 0);

            System.out.println("✅ Dataset preparado con éxito en: " + directorio.getAbsolutePath());

        } catch (Exception e) {
            org.junit.Assert.fail("Error en el test: " + e.getMessage());
        }
    }

    @Test
    public void generarPdfsDesdePlantilla() {

       

        String htmlTemplatePath = TestUtil.getPathResource ("template.html");
        

        System.out.println("\n--- GENERANDO DATASET: PDFs DESDE PLANTILLAS HTML ---");

        File directorioDestino = new File(RUTA_DATA, "dataset-pdfs");

        if (!directorioDestino.exists()) {
            directorioDestino.mkdirs();
        }

        for (int i = 1; i <= 5; i++) {
            File pdfDestino = new File(directorioDestino, "documento_" + i + ".pdf");

            TestUtil.generarPDF(htmlTemplatePath, pdfDestino.getPath());
        }

        System.out.println("✅ Generación de PDFs completada en: " + directorioDestino.getAbsolutePath());
    }

}