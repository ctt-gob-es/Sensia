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


package io.github.sensia.boot;

import io.github.sensia.processor.CensorResources;
import io.github.sensia.processor.img.FaceDetector;
import io.github.sensia.ws.AsyncJobManager;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebListener
public class AppInitListener implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(AppInitListener.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("Jetty Arrancando: Inicializando recursos globales del microservicio...");
        
        try {
            CensorResources.getInstance();
            FaceDetector.getFaceDetector();
            
            log.info("Recursos cargados.");
            
        } catch (Exception e) {
            log.error("ERROR: Fallo al inicializar la memoria. Abortando inicio.", e);
            System.exit(1);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        log.info("Jetty Deteniéndose: Ejecutando Graceful Shutdown...");
        
        try {
            AsyncJobManager.getInstance().shutdown();
            FaceDetector.getFaceDetector().liberarRecursos();
            CensorResources.getInstance().liberarRecursos();
            
        } catch (Exception e) {
            log.error("Error durante la limpieza de memoria nativa. El apagado de la JVM continuará.", e);
        }

        log.info("Microservicio apagado correctamente.");
    }
}