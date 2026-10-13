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

package io.github.sensia.processor;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import ner.Entity;
import ner.NerRequest;
import ner.NerServiceGrpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Cliente gRPC del servicio NER (backend-ner).
 *
 * Variables de entorno:
 *   GRPC_TARGET_HOST, GRPC_TARGET_PORT  dirección del servicio (por defecto 127.0.0.1:50051)
 *   GRPC_TIMEOUT_SECONDS                plazo máximo por petición (por defecto 10)
 *   GRPC_TLS_ENABLED                    true = mTLS con los certificados siguientes
 *   GRPC_TLS_CA, GRPC_TLS_CERT, GRPC_TLS_KEY  CA, certificado y clave PKCS#8 del cliente
 */
public class NerClient {

    private static final Logger log = LoggerFactory.getLogger(NerClient.class);

    private final ManagedChannel channel;
    private final NerServiceGrpc.NerServiceBlockingStub blockingStub;
    private final int timeoutSeconds;

    public NerClient() {
        String targetHost = env("GRPC_TARGET_HOST", "127.0.0.1");
        int targetPort = Integer.parseInt(env("GRPC_TARGET_PORT", "50051"));
        this.timeoutSeconds = Integer.parseInt(env("GRPC_TIMEOUT_SECONDS", "10"));
        boolean tls = Boolean.parseBoolean(env("GRPC_TLS_ENABLED", "true"));

        log.info("[NerClient] Canal hacia {}:{} (mTLS: {}, timeout: {} s)",
                targetHost, targetPort, tls, timeoutSeconds);

        this.channel = Grpc.newChannelBuilderForAddress(targetHost, targetPort, credenciales(tls)).build();
        this.blockingStub = NerServiceGrpc.newBlockingStub(channel);
    }

    private static ChannelCredentials credenciales(boolean tls) {
        if (!tls) {
            log.warn("[NerClient] Canal gRPC sin cifrar (GRPC_TLS_ENABLED=false)");
            return InsecureChannelCredentials.create();
        }
        try {
            return TlsChannelCredentials.newBuilder()
                    .keyManager(fichero("GRPC_TLS_CERT"), fichero("GRPC_TLS_KEY"))
                    .trustManager(fichero("GRPC_TLS_CA"))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("No se pueden leer los certificados gRPC del NER", e);
        }
    }

    private static File fichero(String variable) {
        String ruta = System.getenv(variable);
        if (ruta == null || ruta.isBlank()) {
            throw new IllegalStateException("GRPC_TLS_ENABLED=true pero falta la variable " + variable);
        }
        return new File(ruta);
    }

    private static String env(String nombre, String defecto) {
        return System.getenv().getOrDefault(nombre, defecto);
    }

    public List<Entity> procesarTexto(String text) {
        NerRequest request = NerRequest.newBuilder().setText(text).build();
        try {
            return blockingStub
                    .withDeadlineAfter(timeoutSeconds, TimeUnit.SECONDS)
                    .recognizeEntities(request)
                    .getEntitiesList();
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            switch (code) {
                case DEADLINE_EXCEEDED -> log.error("NER sin respuesta en {} s (DEADLINE_EXCEEDED)", timeoutSeconds);
                case UNAVAILABLE       -> log.error("NER no disponible (UNAVAILABLE). ¿Está parado backend-ner?");
                default                -> log.error("Fallo gRPC con el NER: {}", code);
            }
            throw new RuntimeException("NER gRPC " + code, e);
        }
    }

    public void shutdown() {
        log.info("[NerClient] Cerrando conexión gRPC...");
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}