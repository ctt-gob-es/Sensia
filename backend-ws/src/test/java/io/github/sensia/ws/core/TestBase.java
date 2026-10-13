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

package io.github.sensia.ws.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.Assert;
import org.junit.BeforeClass;

import io.github.sensia.ws.generated.DocumentItem;
import io.github.sensia.ws.generated.ExtractImagesRequest;
import io.github.sensia.ws.generated.ExtractImagesResponse;
import io.github.sensia.ws.generated.GetStatusResponse;
import io.github.sensia.ws.generated.ProcessingOptions;
import io.github.sensia.ws.generated.RedactImageRequest;
import io.github.sensia.ws.generated.RedactImageResponse;

public abstract class TestBase {

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
    protected static final String RUTA_OUTPUT = System.getProperty("test.output.dir", "./test-output/");
    protected static final String REPORT_NAME = System.getProperty("test.report.name", "resultado.txt");
    

    private static boolean entornoPreparado = false;

    @BeforeClass
    public static void inicializarEntornoGlobal() {
        boolean saltarPreparacion = Boolean.parseBoolean(System.getProperty("test.skip.dataset", "false"));

        if (saltarPreparacion) {
            if (!entornoPreparado) {
                System.out.println("[INFO] ⏩ Saltando la preparación de datasets (test.skip.dataset=true)");
                entornoPreparado = true;
            }
            return;
        }

        if (!entornoPreparado) {
            new io.github.sensia.ws.setup.DatasetManager().prepararTodosLosDatasets();
            entornoPreparado = true;
        }
    }

    @FunctionalInterface
    public interface OperacionSensia {
        void ejecutar(TestEngine motor, File[] archivos, String testId, File dirSalida) throws Exception;
    }

    protected OperacionSensia operacionRedactImage(Consumer<RedactImageRequest> configurador) {
        return (motor, archivos, testId, dirSalida) -> {

            long inicioMs = System.currentTimeMillis();

            if (archivos == null || archivos.length != 1) 
                throw new IllegalArgumentException("Se requiere exactamente un archivo.");

            RedactImageResponse response = motor.ejecutarRedactImage(testId, archivos[0].getAbsolutePath(), configurador);

            guardarResultadosRedactImage(dirSalida, archivos[0], testId, response, inicioMs);
        };
    }

    private void guardarResultadosRedactImage(File dirSalida, File archivo, String testId, RedactImageResponse response,
            long inicioMs) {
        long tiempoMs = System.currentTimeMillis() - inicioMs;
        System.out.println("   [ServiceStatus] Resultado: " + response.getStatus().getResult());

        if (response.getImage() != null && response.getImage().getFileContent() != null) {

            File imgSalida = new File(dirSalida, TestUtil.anadirSufijo(archivo.getName(), "_redacted"));

            TestUtil.guardarFichero(imgSalida.getAbsolutePath(), response.getImage().getFileContent());
        }

        File fileReport = new File(dirSalida, REPORT_NAME);
        TestUtil.guardarInforme(fileReport.getAbsolutePath(), testId, response.getImage().getFileName(),
                response.getRedactionResults(), tiempoMs);
    }

    protected OperacionSensia operacionExtractImages(Consumer<ExtractImagesRequest> configurador) {
        return (motor, archivos, testId, dirSalida) -> {

            if (archivos == null || archivos.length != 1) 
                throw new IllegalArgumentException("Se requiere exactamente un archivo.");

            ExtractImagesResponse response = motor.ejecutarExtractImages(testId, archivos[0].getAbsolutePath(),
                    configurador);
                    
            guardarResultadosExtractImages(dirSalida, archivos[0], response);
        };
    }

    private void guardarResultadosExtractImages(File dirSalida, File archivo, ExtractImagesResponse response) {
        if (response.getImages() != null && !response.getImages().getDocument().isEmpty()) {
            int index = 1;
            for (DocumentItem img : response.getImages().getDocument()) {
                File outFile = new File(dirSalida, TestUtil.cambiarExtension(TestUtil.anadirSufijo(archivo.getName(), "_extracted_" + index), ".png"));
                TestUtil.guardarFichero(outFile.getAbsolutePath(), img.getFileContent());
                index++;
            }
        }
    }

    protected OperacionSensia operacionSubmitBatch(Consumer<ProcessingOptions> configurador) {
        return (motor, archivos, testId, dirSalida) -> {
            List<GetStatusResponse> resultados = motor.ejecutarSumitBatch(testId, archivos,
                    configurador);
            guardarResultadosSubmitBatch(dirSalida, resultados);
        };
    }

    private void guardarResultadosSubmitBatch(File dirSalida, List<GetStatusResponse> resultados) {
        for (GetStatusResponse res : resultados) {
            if (res.getProcessedDocument() != null) {
                String nombreOriginal = res.getProcessedDocument().getFileName();
                File outFile = new File(dirSalida, TestUtil.anadirSufijo(nombreOriginal, "_out"));
                TestUtil.guardarFichero(outFile.getAbsolutePath(), res.getProcessedDocument().getFileContent());
            }
        }
    }

    protected void procesarArchivo(String rutaArchivo, String testId, OperacionSensia operacion) {
        System.out.println("==================================================");
        System.out.println("  EJECUTANDO PRUEBA INDIVIDUAL: " + testId);
        System.out.println("  Archivo: " + rutaArchivo);
        System.out.println("==================================================");

        File archivo = new File(rutaArchivo);
        if (!archivo.exists() || !archivo.isFile()) {
            Assert.fail("❌ El archivo de entrada no existe o no es válido: " + rutaArchivo);
            return;
        }

        File dirSalida = new File(RUTA_OUTPUT, testId);
        limpiarDirectorio(dirSalida);
        TestEngine motor = iniciarMotor();

        long inicioMs = System.currentTimeMillis();

        try {
            operacion.ejecutar(motor, new File[] {archivo}, testId, dirSalida);
            long tiempo = System.currentTimeMillis() - inicioMs;
            System.out.println("✅ [ÉXITO] Archivo procesado correctamente en " + tiempo + " ms.");
        } catch (Exception e) {
            Assert.fail("❌ Fallo procesando el archivo " + archivo.getName() + ": " + e.getMessage());
        }
    }

    protected void procesarDirectorio(String rutaDirectorioInput, String prefijoTest, OperacionSensia operacion) {
        procesarDirectorio(rutaDirectorioInput, prefijoTest, 1, 1, operacion);
    }

    protected void procesarDirectorio(String rutaDirectorioInput, String prefijoTest, int numHilos, OperacionSensia operacion) {
        procesarDirectorio(rutaDirectorioInput, prefijoTest, numHilos, 1, operacion);
    }

    protected void procesarDirectorio(String rutaDirectorioInput, String prefijoTest, int numHilos, int batchSize,
            OperacionSensia operacion) {
        System.out.println("==================================================");
        System.out.println("  EJECUTANDO BATERÍA: " + prefijoTest);
        System.out.println("  Origen: " + rutaDirectorioInput);
        System.out.println("  Modo: " + (numHilos <= 1 ? "SECUENCIAL (Debug)" : "PARALELO (" + numHilos + " hilos)"));
        System.out.println("==================================================");

        File directorio = new File(rutaDirectorioInput);
        if (!directorio.exists() || !directorio.isDirectory()) {
            Assert.fail("❌ El directorio de entrada no existe: " + rutaDirectorioInput);
            return;
        }

        File[] archivos = directorio.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                    lower.endsWith(".png") || lower.endsWith(".pdf");
        });

        if (archivos == null || archivos.length == 0) {
            System.out.println("[INFO] No se encontraron archivos válidos.");
            return;
        }

        File dirSalida = new File(RUTA_OUTPUT, prefijoTest);
        limpiarDirectorio(dirSalida);
        TestEngine motor = iniciarMotor();

        AtomicInteger procesadasExito = new AtomicInteger(0);
        AtomicInteger fallosServicio = new AtomicInteger(0);

        long inicioGlobal = System.currentTimeMillis();
        System.out.println("[INFO] Archivos a procesar: " + archivos.length);

        if (numHilos <= 1) {
            for (int i = 0; i < archivos.length; i+=batchSize) {
            int fin = Math.min(i + batchSize, archivos.length);
    
            File[] lote = Arrays.copyOfRange(archivos, i, fin);
    
            String testId = String.format("%s-%03d-batch", prefijoTest, (i / batchSize) + 1);

                try {
                    operacion.ejecutar(motor, lote, testId, dirSalida);
                    procesadasExito.incrementAndGet();
                } catch (Exception e) {
                    fallosServicio.incrementAndGet();
                    System.err.println("\n[X] Fallo procesando " + testId + ": " + e.getMessage());
                }
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(numHilos);
            for (int i = 0; i < archivos.length; i+=batchSize) {
                int fin = Math.min(i + batchSize, archivos.length);
    
                final File[] lote = Arrays.copyOfRange(archivos, i, fin);
                final String batchId = String.format("%s-batch-%03d", prefijoTest, (i / batchSize) + 1);

                executor.submit(() -> {
                    try {
                        operacion.ejecutar(motor, lote, batchId, dirSalida);
                        procesadasExito.addAndGet(lote.length);
                        System.out.print("+"); // Indicador visual
                    } catch (Exception e) {
                        fallosServicio.incrementAndGet();
                        System.err.println("\n[X] Fallo en " + batchId + ": " + e.getMessage());
                    }
                });
            }
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                System.err.println("\n[!] La prueba fue interrumpida.");
            }
        }

        long tiempoTotalTestMs = System.currentTimeMillis() - inicioGlobal;

        System.out.println("\n\n==================================================");
        System.out.println("               REPORTE FINAL: " + prefijoTest);
        System.out.println("==================================================");
        System.out.printf(" - Total archivos:            %d%n", archivos.length);
        System.out.printf(" - Procesados (ÉXITO):        %d%n", procesadasExito.get());
        System.out.printf(" - Fallos (ERROR):            %d%n", fallosServicio.get());
        System.out.printf(" - Tiempo TOTAL ejecución:    %d ms (%.2f seg)%n", tiempoTotalTestMs,
                tiempoTotalTestMs / 1000.0);

        if (numHilos > 1 && procesadasExito.get() > 0) {
            System.out.printf(" - Rendimiento medio:         %.2f reqs/seg%n",
                    (double) procesadasExito.get() / (tiempoTotalTestMs / 1000.0));
        }
        System.out.println("==================================================\n");

        if (fallosServicio.get() > 0) {
            Assert.fail("La batería finalizó con " + fallosServicio.get() + " fallos.");
        }
    }

    protected void limpiarDirectorio(File directorio) {
        if (directorio.exists()) {
            for (File f : directorio.listFiles()) {
                if (f.isFile())
                    f.delete();
            }
        } else {
            directorio.mkdirs();
        }
    }

    private TestEngine iniciarMotor() {
        try {
            return new TestEngine();
        } catch (Exception e) {
            Assert.fail("❌ No se pudo iniciar SensiaTestEngine: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

}
