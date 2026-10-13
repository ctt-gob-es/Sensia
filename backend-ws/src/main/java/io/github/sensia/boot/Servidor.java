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

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.server.handler.SizeLimitHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import java.time.Duration;

import org.apache.cxf.BusFactory;
import org.apache.cxf.jaxws.EndpointImpl;
import org.apache.cxf.transport.servlet.CXFNonSpringServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.ws.AuthCertInterceptor;
import io.github.sensia.ws.AuthTokenInterceptor;
import io.github.sensia.ws.ConfigManager;
import io.github.sensia.ws.SensiaServiceImpl;

public class Servidor {

    private static final Logger log = LoggerFactory.getLogger(Servidor.class);

    public static void main(String[] args) throws Exception {

        System.setProperty("org.apache.cxf.Logger", "org.apache.cxf.common.logging.Slf4jLogger");

        ConfigManager config = ConfigManager.getInstance("server.properties");
        int port = Integer.parseInt(config.getConfig("jetty.http.port", "8080"));
        int maxThreads = Integer.parseInt(config.getConfig("jetty.threadpool.maxThreads", "200"));
        int minThreads = Integer.parseInt(config.getConfig("jetty.threadpool.minThreads", "10"));
        int poolIdleTimeout = Integer.parseInt(config.getConfig("jetty.threadpool.idleTimeoutMs", "60000"));
        int connIdleTimeout = Integer.parseInt(config.getConfig("jetty.connector.idleTimeoutMs", "30000"));
        int qosMaxRequests = Integer.parseInt(config.getConfig("jetty.qos.maxRequests", "50"));
        int qosSuspendMs = Integer.parseInt(config.getConfig("jetty.qos.suspendMs", "5000"));
        // Tamaño máximo del cuerpo de cualquier petición (SOAP incluido), en bytes.
        // 70 MB: un PDF de 50 MB en base64 ocupa unos 67 MB.
        int maxPostSize = Integer.parseInt(config.getConfig("jetty.http.maxPostSize", "73400320"));
        boolean httpEnabled = Boolean.parseBoolean(config.getConfig("jetty.http.enabled", "true"));
        boolean sslEnabled = Boolean.parseBoolean(config.getConfig("jetty.ssl.enabled", "false"));
        int sslPort = Integer.parseInt(config.getConfig("jetty.ssl.port", "8443"));
        boolean httpsEnabled = Boolean.parseBoolean(config.getConfig("jetty.https.enabled", "false"));
        int httpsPort = Integer.parseInt(config.getConfig("jetty.https.port", "443"));
        log.info(">>> Iniciando microservicio SenSIA");
        log.info(" Puerto de escucha HTTP:          {}", port);
        log.info(" Pool de Hilos - Máximo:          {}", maxThreads);
        log.info(" Pool de Hilos - Mínimo:          {}", minThreads);
        log.info(" Pool Timeout (inactividad):      {} ms", poolIdleTimeout);
        log.info(" Conector HTTP Timeout:           {} ms", connIdleTimeout);
        log.info(" QoS - Peticiones simultáneas:    {}", qosMaxRequests);
        log.info(" QoS - Max espera en cola:        {} ms", qosSuspendMs);
        log.info(" Límite cuerpo petición (bytes):  {}", maxPostSize);
        log.info("=========================================================");

        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setName("sensia-ws-pool");
        threadPool.setMaxThreads(maxThreads);
        threadPool.setMinThreads(minThreads);
        threadPool.setIdleTimeout(poolIdleTimeout);

        Server server = new Server(threadPool);

        server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", maxPostSize);

        // Validar que al menos un protocolo esté habilitado
         if (!httpEnabled && !sslEnabled && !httpsEnabled) {
            log.error(
                    "ERROR: Ni HTTP ni HTTPS/mTLS están habilitados en la configuración. El servidor no puede escuchar peticiones.");
            System.exit(1);
        }

        // ==========================================
        // CONECTOR HTTP(interno, nginx o desarrollo)
        // ==========================================
        if (httpEnabled) {
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setPort(port);
            httpConnector.setIdleTimeout(connIdleTimeout);
            server.addConnector(httpConnector);
            log.info("Conector HTTP habilitado en el puerto: {}", port);
        }

        // ==========================================
        // CONECTOR HTTPS CON TOKEN (sin certificado)
        // ==========================================
        if (httpsEnabled) {
            ConfigManager secureConf = ConfigManager.getInstance("security.properties");
            SslContextFactory.Server tokenSsl = new SslContextFactory.Server();
            tokenSsl.setKeyStorePath(secureConf.getConfig("jetty.ssl.keystore.path", "keystore.p12"));
            tokenSsl.setKeyStorePassword(secureConf.getConfig("jetty.ssl.keystore.password", "changeit"));
            tokenSsl.setNeedClientAuth(false);

            HttpConfiguration tokenConfig = new HttpConfiguration();
            tokenConfig.addCustomizer(new SecureRequestCustomizer());

            ServerConnector tokenConnector = new ServerConnector(server,
                    new SslConnectionFactory(tokenSsl, "http/1.1"),
                    new HttpConnectionFactory(tokenConfig));
            tokenConnector.setPort(httpsPort);
            tokenConnector.setIdleTimeout(connIdleTimeout);
            server.addConnector(tokenConnector);
            log.info("Conector HTTPS (token) habilitado en el puerto: {}", httpsPort);
        }

        // ==========================================
        // CONECTOR HTTPS (CIFRADO / mTLS)
        // ==========================================
        if (sslEnabled) {
            ConfigManager secureConf = ConfigManager.getInstance("security.properties");
            String keystorePath = secureConf.getConfig("jetty.ssl.keystore.path", "keystore.p12");
            String keystorePass = secureConf.getConfig("jetty.ssl.keystore.password", "changeit");
            String truststorePath = secureConf.getConfig("jetty.ssl.truststore.path", "truststore.p12");
            String truststorePass = secureConf.getConfig("jetty.ssl.truststore.password", "changeit");
            boolean requireClientAuth = Boolean.parseBoolean(secureConf.getConfig("jetty.ssl.requireClientAuth", "true"));
            boolean validatePeerCerts = Boolean.parseBoolean(secureConf.getConfig("jetty.ssl.validatePeerCerts", "false"));
            boolean enableCRLDP = Boolean.parseBoolean(secureConf.getConfig("jetty.ssl.enableCRLDP", "false"));
            boolean enableOCSP = Boolean.parseBoolean(secureConf.getConfig("jetty.ssl.enableOCSP", "false"));

            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath(keystorePath);
            sslContextFactory.setKeyStorePassword(keystorePass);
            sslContextFactory.setTrustStorePath(truststorePath);
            sslContextFactory.setTrustStorePassword(truststorePass);
            sslContextFactory.setNeedClientAuth(requireClientAuth);
            sslContextFactory.setValidatePeerCerts(validatePeerCerts);
            sslContextFactory.setEnableCRLDP(enableCRLDP);
            sslContextFactory.setEnableOCSP(enableOCSP);

            HttpConfiguration httpsConfig = new HttpConfiguration();
            httpsConfig.addCustomizer(new SecureRequestCustomizer());

            ServerConnector httpsConnector = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, "http/1.1"),
                    new HttpConnectionFactory(httpsConfig));

            httpsConnector.setPort(sslPort);
            httpsConnector.setIdleTimeout(connIdleTimeout);
            server.addConnector(httpsConnector);
            log.info("Conector HTTPS (mTLS) habilitado en el puerto: {}", sslPort);
        }

        // Sin sesiones HTTP: el servicio no las usa
        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.setMaxFormContentSize(maxPostSize);

        Slf4jRequestLogWriter logWriter = new Slf4jRequestLogWriter();
        logWriter.setLoggerName("org.eclipse.jetty.server.RequestLog");

        RequestLog requestLog = new CustomRequestLog(logWriter, CustomRequestLog.EXTENDED_NCSA_FORMAT);
        server.setRequestLog(requestLog);

        contextHandler.addEventListener(new AppInitListener());
        QoSHandler qosHandler = new QoSHandler();
        qosHandler.setMaxRequestCount(qosMaxRequests);
        qosHandler.setMaxSuspend(Duration.ofMillis(qosSuspendMs));
        qosHandler.setHandler(contextHandler);

        CXFNonSpringServlet cxfServlet = new CXFNonSpringServlet();
        ServletHolder cxfServletHolder = new ServletHolder(cxfServlet);

        contextHandler.addServlet(cxfServletHolder, "/services/*");

        // Límite de tamaño del cuerpo para cualquier petición, no solo formularios.
        // Va por fuera de la QoS: una petición demasiado grande se rechaza (413)
        // sin ocupar plaza de procesamiento. Imprescindible en el puerto mTLS,
        // que no pasa por nginx. Las respuestas no se limitan (-1).
        SizeLimitHandler sizeLimitHandler = new SizeLimitHandler(maxPostSize, -1);
        sizeLimitHandler.setHandler(qosHandler);
        server.setHandler(sizeLimitHandler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Señal de apagado (SIGTERM/SIGINT) recibida del SO/Docker. Deteniendo Jetty...");
            try {
                server.stop();
            } catch (Exception e) {
                log.error("Error durante el apagado del servidor Jetty", e);
            }
        }));

        log.info("Iniciando motor Jetty y Apache CXF...");
        server.start();

        // ENDPOINT
        BusFactory.setDefaultBus(cxfServlet.getBus());
        @SuppressWarnings("resource") // se detiene con Jetty
        EndpointImpl cxfEndpoint = new EndpointImpl(cxfServlet.getBus(), new SensiaServiceImpl());
        cxfEndpoint.getInInterceptors().add(new AuthCertInterceptor());
        cxfEndpoint.getInInterceptors().add(new AuthTokenInterceptor());
        cxfEndpoint.publish("/SensiaService");

        log.info("Microservicio iniciado correctamente.");
        log.info("WSDL disponible en /services/SensiaService?wsdl");
        log.info("STATUS: READY");

        server.join();
    }
}