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
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

import java.util.concurrent.*;

public class OcrResourcesManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(OcrResourcesManager.class);
    
    private final GenericObjectPool<ITesseract> tesseractPool;
    private final ExecutorService ocrExecutor;

    public OcrResourcesManager() {

        int cpuCores = Runtime.getRuntime().availableProcessors();
        int optimalPoolSize = Math.max(2, cpuCores);
        
        logger.info("Inicializando OcrResourcesManager para {} cores...", cpuCores);

        GenericObjectPoolConfig<ITesseract> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(optimalPoolSize);
        poolConfig.setMaxIdle(optimalPoolSize);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(false);
        poolConfig.setJmxEnabled(false);
        
        this.tesseractPool = new GenericObjectPool<>(
                new TesseractInstanceFactory(), 
                poolConfig
        );

        int maxQueueSize = optimalPoolSize * 2; 
        
        this.ocrExecutor = new ThreadPoolExecutor(
                optimalPoolSize,                   // Hilos base
                optimalPoolSize,                   // Hilos máximos permitidos
                60L, TimeUnit.SECONDS,             // Tiempo para matar hilos inactivos
                new ArrayBlockingQueue<>(maxQueueSize), // Cola limitada
                new ThreadPoolExecutor.CallerRunsPolicy() // hilo extra ayuda si hay atasco
        );
    }

    
    public <T> Future<T> submitOcrTask(Callable<T> task) {
        return ocrExecutor.submit(task);
    }

    /**
     * Toma una instancia de Tesseract. Debe ser devuelta siempre en un bloque finally!
     */
    public ITesseract borrowTesseract() throws Exception {
        return tesseractPool.borrowObject();
    }

    /**
     * Devuelve la instancia al pool.
     */
    public void returnTesseract(ITesseract tesseract) {
        if (tesseract != null) {
            tesseractPool.returnObject(tesseract);
        }
    }

    
    @PreDestroy
    @Override
    public void close() {
        logger.info("Iniciando apagado de OcrResourcesManager...");
        
        ocrExecutor.shutdown();
        try {
            if (!ocrExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Forzando apagado de hilos OCR atascados...");
                ocrExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ocrExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        tesseractPool.close();
        logger.info("OcrResourcesManager apagado correctamente.");
    }
}