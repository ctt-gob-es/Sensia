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
import org.apache.cxf.headers.Header;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.Phase;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import jakarta.xml.ws.handler.MessageContext;
import jakarta.xml.ws.handler.MessageContext.Scope;

import java.util.HashMap;
import java.util.Map;
import javax.xml.namespace.QName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AuthTokenInterceptor extends AbstractSoapInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenInterceptor.class);

    /** Propiedad del contexto con la clave del usuario final (AuthHeader/cliente) */
    public static final String CLIENTE = "SENSIA_CLIENTE";
    private static final int MAX_LONGITUD_CLIENTE = 128;

    public AuthTokenInterceptor() {
        super(Phase.PRE_PROTOCOL);
    }

    @Override
    public void handleMessage(SoapMessage message) throws Fault {

        // La descarga del WSDL o del XSD (GET ?wsdl, ?xsd) no lleva cabecera SOAP. Con GET no se
        String metodo = (String) message.get(Message.HTTP_REQUEST_METHOD);
        String consulta = (String) message.get(Message.QUERY_STRING);
        if ("GET".equalsIgnoreCase(metodo) && consulta != null
                && (consulta.toLowerCase().startsWith("wsdl") || consulta.toLowerCase().startsWith("xsd"))) {
            return;
        }


        // Recuperamos el Trace ID que generó el AuthCertInterceptor
        String traceId = (String) message.get("SENSIA_TRACE_ID");
        if (traceId == null) traceId = "UNKNOWN";

        boolean isTokenValid = false;
        String cliente = null;

        try {
            Header authHeader = message.getHeader(new QName("http://sensia.github.io/", "AuthHeader"));

            if (authHeader != null && authHeader.getObject() instanceof Element) {
                Element headerEl = (Element) authHeader.getObject();
                NodeList tokenNodes = headerEl.getElementsByTagNameNS("*", "token");

                if (tokenNodes.getLength() > 0) {
                    String tokenRecibido = tokenNodes.item(0).getTextContent();
                    String tokenEsperado = ConfigManager.getInstance("security.properties").getConfig("auth.token");
                    
                    if (tokenEsperado != null && !tokenEsperado.isBlank() && tokenRecibido != null
                            && MessageDigest.isEqual(tokenEsperado.getBytes(StandardCharsets.UTF_8),
                                                     tokenRecibido.getBytes(StandardCharsets.UTF_8))) {
                        isTokenValid = true;
                        log.info("[{}] Token SOAP válido.", traceId);

                        // Generación de sellos internos de SensIA
                        String seal = SecuritySealGenerator.getValidSeal();
                        message.put("SENSIA_INTERNAL_SEAL", seal);

                        @SuppressWarnings("unchecked")
                        Map<String, Scope> scopes = (Map<String, Scope>) message
                                .get("jakarta.xml.ws.handler.MessageContext.PROPERTY_SCOPE");

                        if (scopes == null) {
                            scopes = new HashMap<>();
                            message.put("jakarta.xml.ws.handler.MessageContext.PROPERTY_SCOPE", scopes);
                        }

                        scopes.put("SENSIA_INTERNAL_SEAL", MessageContext.Scope.APPLICATION);
                        message.getExchange().put("SENSIA_SEGURIDAD_OK", SecuritySealGenerator.getValidSeal());

                        // Clave del usuario final. Solo con token válido.
                        NodeList clienteNodes = headerEl.getElementsByTagNameNS("*", "cliente");
                        String valor = clienteNodes.getLength() > 0 ? clienteNodes.item(0).getTextContent().trim() : "";
                        if (!valor.isEmpty() && valor.length() <= MAX_LONGITUD_CLIENTE) {
                            cliente = valor;
                            message.put(CLIENTE, cliente);
                            scopes.put(CLIENTE, MessageContext.Scope.APPLICATION);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignoramos el error de parseo para forzar el fallo de seguridad más abajo
            log.info("[{}] Error al procesar el token SOAP. Causa: {}", traceId, e.getMessage());
        }

        if (!isTokenValid) {
            log.error("[{}] Autenticación denegada. Token inválido o ausente.", traceId);
            throw AuthTokenInterceptor.faultAutenticacion("Autenticación denegada. Token inválido.", "Client.Auth");
        }

        if (cliente == null) {
            log.error("[{}] Petición rechazada. Cliente ausente, vacío o de más de {} caracteres.", traceId,
                    MAX_LONGITUD_CLIENTE);
            throw AuthTokenInterceptor.faultAutenticacion(
                    "Petición rechazada. AuthHeader debe incluir <cliente> (1 a " + MAX_LONGITUD_CLIENTE + " caracteres).",
                    "Client.Auth");
        }
    }


    static Fault faultAutenticacion(String mensaje, String codigoFault) {
        Fault fault = new Fault(new Exception(mensaje));
        fault.setFaultCode(new QName("http://schemas.xmlsoap.org/soap/envelope/", codigoFault));

        Element detail = fault.getOrCreateDetail();
        Document doc = detail.getOwnerDocument();
        Element serviceFault = doc.createElementNS("http://sensia.github.io/", "sen:ServiceFault");
        Element errorCode = doc.createElementNS(null, "errorCode");
        errorCode.setTextContent("AUTH_ERROR");

        Element errorMessage = doc.createElementNS(null, "errorMessage");
        errorMessage.setTextContent(mensaje);
        serviceFault.appendChild(errorCode);
        serviceFault.appendChild(errorMessage);
        detail.appendChild(serviceFault);
        return fault;
    }
}