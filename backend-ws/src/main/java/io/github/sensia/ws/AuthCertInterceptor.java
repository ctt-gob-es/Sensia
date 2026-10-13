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


package io.github.sensia.ws;

import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.transport.http.AbstractHTTPDestination;

import jakarta.servlet.http.HttpServletRequest;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthCertInterceptor extends AbstractSoapInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthCertInterceptor.class);

    public AuthCertInterceptor() {
        super(Phase.RECEIVE);
    }

    @Override
    public void handleMessage(SoapMessage message) throws Fault {

        HttpServletRequest request = (HttpServletRequest) message.get(AbstractHTTPDestination.HTTP_REQUEST);

        String traceId = (String) message.get("SENSIA_TRACE_ID");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
            message.put("SENSIA_TRACE_ID", traceId);
        }

        if (request != null && request.getAttribute("jakarta.servlet.request.cipher_suite") != null) {
            X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
            
            if (certs != null && certs.length > 0) {
                X509Certificate clientCert = certs[0];
                
                try {
                    String fingerprintRecibido = calcularSHA256(clientCert);
                    String subjectDN = clientCert.getSubjectX500Principal().getName();
                    
                    log.info("[{}] Certificado recibido de: {}", traceId, subjectDN);
                    log.info("[{}] Fingerprint calculado: {}", traceId, fingerprintRecibido);

                   
                    if (!isFingerprintAutorizado(fingerprintRecibido)) {
                        log.error("[{}] Acceso denegado: El Fingerprint {} NO está en la lista blanca.", traceId, fingerprintRecibido);
                        throw AuthTokenInterceptor.faultAutenticacion(
                                "Acceso denegado: Certificado no autorizado para este servicio.", "Client.Unauthorized");
                    }
                    
                    log.info("[{}] Certificado autorizado correctamente por Fingerprint.", traceId);
                } catch (Fault f) {
                    throw f;
                } catch (Exception e) {
                    log.error("[{}] Error procesando la criptografía del certificado: {}", traceId, e.getMessage());
                    throw new Fault(e);
                }

            } else if (request.getLocalPort() == ConfigManager.getInstance("server.properties")
                    .getIntConfig("jetty.ssl.port", 8443)) {
                log.error("[{}] Acceso denegado: El canal es seguro pero falta el certificado cliente.", traceId);
                throw AuthTokenInterceptor.faultAutenticacion(
                        "Acceso denegado: Certificado cliente requerido.", "Client.Auth");
            } else {
                log.info("[{}] HTTPS sin certificado (puerto de token): se autentica solo por token.", traceId);
            }
        }
    }

   
    
    private String calcularSHA256(X509Certificate cert) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(cert.getEncoded());
        StringBuilder fingerprint = new StringBuilder();
        for (byte b : digest) {
            fingerprint.append(String.format("%02X", b));
        }
        return fingerprint.toString(); // Devuelve algo como "A1B2C3D4..."
    }

   
    
    private boolean isFingerprintAutorizado(String fingerprintRecibido) {
        try {
            String whitelistStr = ConfigManager.getInstance("security.properties").getConfig("auth.cert.whitelist");
            
            if (whitelistStr == null || whitelistStr.trim().isEmpty()) {
                log.warn("La 'auth.cert.whitelist' está vacía. Bloqueando acceso.");
                return false; 
            }

            List<String> permitidos = Arrays.asList(whitelistStr.split(";"));
            
            for (String permitido : permitidos) {
                String hashLimpio = permitido.replace(":", "").trim();
                
                if (fingerprintRecibido.equalsIgnoreCase(hashLimpio)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error al leer la whitelist: {}", e.getMessage());
        }
        
        return false;
    }
}