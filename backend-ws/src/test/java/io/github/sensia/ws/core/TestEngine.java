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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import org.apache.cxf.headers.Header;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.ws.BindingProvider;
import io.github.sensia.ws.generated.*;

public class TestEngine {

    private final String endpoint;
    private final String token;
    private final String clienteId;
    private final SensiaServicePortType cliente;

    public TestEngine() throws JAXBException, IOException {
        this.endpoint = System.getProperty("sensia.endpoint", "http://localhost:7070/services/SensiaService");
        String token  = System.getenv("AUTH_TOKEN");
        String clienteId  = "cliente-test";
        if (token  == null || token .isBlank()) {
            token = System.getProperty("sensia.token", System.getenv("AUTH_TOKEN"));
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(
                    "Falta el token: define AUTH_TOKEN (make dev-env lo genera en .env) o -Dsensia.token");
            }
        }
        this.token=token;
        this.clienteId=clienteId;
        this.cliente = configurarCliente();
    }

    private SensiaServicePortType configurarCliente() throws JAXBException {
        JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
        factory.setServiceClass(SensiaServicePortType.class);
        factory.setAddress(endpoint);
        SensiaServicePortType client = (SensiaServicePortType) factory.create();

        AuthHeader authHeader = new AuthHeader();
        authHeader.setToken(token);
        authHeader.setCliente(clienteId);
        Header cxfHeader = new Header(new QName("http://sensia.github.io/", "AuthHeader"),
                authHeader, new JAXBDataBinding(AuthHeader.class));
        List<Header> headers = new ArrayList<>();
        headers.add(cxfHeader);
        ((BindingProvider) client).getRequestContext().put(Header.HEADER_LIST, headers);

        return client;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public List<GetStatusResponse> ejecutarSumitBatch(String clientId, File[] files,
            Consumer<ProcessingOptions> configuradorOpciones) {
        List<GetStatusResponse> resultadosFinales = new ArrayList<>();
        try {

            if (files == null) {
                System.out.println(">> Ejecutando Lote Asíncrono: " + clientId + " no se ha ejectuado porque no se indican ficheros.");
                return new ArrayList<GetStatusResponse>();
            }

            System.out.println(">> Ejecutando Lote Asíncrono: " + clientId + " nº de ficheros " + files.length);

            SubmitBatchRequest request = new SubmitBatchRequest();
            ProcessingOptions options = new ProcessingOptions();
            configuradorOpciones.accept(options);
            request.setOptions(options);

            for (File f : files) {
                System.out.println(">> Ejecutando Lote Asíncrono: " + clientId + " ficheros " + f.getName());
                request.getDocuments().add(crearItemLote(clientId, f.getAbsolutePath()));
            }

            SubmitBatchResponse response = cliente.submitBatch(request);
            imprimirDetalleRespuesta(response);

            if (response.getTickets() == null || response.getTickets().isEmpty()) {
                System.out.println("   [!] El servidor no devolvió tickets.");
                return resultadosFinales;
            }

            response.getTickets().parallelStream().forEach(ticket -> {
                String ticketId = ticket.getServerTicketId();
                if (ticketId != null && !ticketId.isEmpty()) {
                    try {
                        GetStatusResponse statusFinal = consultarEstado(ticketId);
                        if (statusFinal != null)
                            resultadosFinales.add(statusFinal);
                    } catch (InterruptedException e) {
                        System.err.println("   [X] Fallo en polling del ticket " + ticketId + ": " + e.getMessage());
                    }
                }
            });

        } catch (

        Exception e) {
            TestUtil.manejarExcepcion(clientId, e);
        }
        return resultadosFinales;
    }

    public RedactImageResponse ejecutarRedactImage(String testId, String fileName,
            Consumer<RedactImageRequest> configurador) {
        try {
            System.out.println("\n>> Ejecutando RedactImage Síncrono: " + testId + " [" + fileName + "]");

            RedactImageRequest request = new RedactImageRequest();
            request.setImage(crearDocumentoItem(fileName));
            if (configurador != null)
                configurador.accept(request);

            RedactImageResponse response = cliente.redactImage(request);

            return response;
        } catch (Exception e) {
            TestUtil.manejarExcepcion(testId, e);
            throw new RuntimeException("Fallo interno en el motor: " + e.getMessage(), e);
        }
    }

    public ExtractImagesResponse ejecutarExtractImages(String testId, String fileName,
            Consumer<ExtractImagesRequest> configurador) {
        try {
            System.out.println("\n>> Ejecutando ExtractImages Síncrono: " + testId + " [" + fileName + "]");
            ExtractImagesRequest request = new ExtractImagesRequest();
            request.setDocument(crearDocumentoItem(fileName));
            if (configurador != null)
                configurador.accept(request);

            return cliente.extractImages(request);
        } catch (Exception e) {
            TestUtil.manejarExcepcion(testId, e);
            throw new RuntimeException("Fallo interno en el motor: " + e.getMessage(), e);
        }
    }

    private GetStatusResponse consultarEstado(String ticketId) throws InterruptedException {
        boolean terminado = false;
        int intentos = 0;
        int maxIntentos = Integer.parseInt(System.getProperty("sensia.polling.retries", "20"));
        int esperaMs = Integer.parseInt(System.getProperty("sensia.polling.ms", "5000"));

        while (!terminado && intentos < maxIntentos) {
            GetStatusRequest req = new GetStatusRequest();
            req.setTicketId(ticketId);

            try {
                GetStatusResponse res = cliente.getStatus(req);
                ProcessStateEnum estado = res.getTaskStatus().getProcessState();
                System.out.println("   - Estado Ticket " + ticketId + ": " + estado);

                if (estado == ProcessStateEnum.COMPLETED) {
                    imprimirDetalleGetStatusResponse(res);
                    return res;

                } else if (estado == ProcessStateEnum.ERROR || estado == ProcessStateEnum.AUTH_ERROR) {
                    System.out.println("   [!] Error en proceso: " + res.getTaskStatus().getMessage());
                    terminado = true;
                } else {
                    Thread.sleep(esperaMs);
                    intentos++;
                    esperaMs+=esperaMs*intentos/10f;
                }
            } catch (ServiceFaultMessage e) {
                System.err.println("   [X] Error SOAP al consultar ticket: " + e.getMessage());
                terminado = true;
            } catch (Exception e) {
                TestUtil.manejarExcepcion(ticketId, e);
                throw new RuntimeException("Fallo interno en el motor: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException(" [X] Error al consultar ticket: demasiado tiempo en espera tras " + intentos + " intentos");
        
    }

    private BatchDocumentItem crearItemLote(String clientId, String fileName) throws IOException {
        BatchDocumentItem item = new BatchDocumentItem();
        item.setClientId(clientId);
        item.setDocument(crearDocumentoItem(fileName));
        return item;
    }

    private DocumentItem crearDocumentoItem(String pathToFile) throws IOException {

        java.nio.file.Path path = java.nio.file.Paths.get(pathToFile);

        if (!java.nio.file.Files.exists(path)) {
            throw new IOException("No se encontró el archivo en la ruta: " + pathToFile);
        }

        byte[] contenido = java.nio.file.Files.readAllBytes(path);

        DocumentItem doc = new DocumentItem();

        String fileName = path.getFileName().toString();
        doc.setFileName(fileName);
        doc.setFileContent(contenido);

        doc.setContentType(fileName.toLowerCase().endsWith(".pdf") ? "application/pdf" : "image/jpeg");

        return doc;
    }

    private static void imprimirDetalleRespuesta(SubmitBatchResponse response) {
        if (response == null) {
            System.out.println("La respuesta es nula.");
            return;
        }

        ServiceStatus status = response.getStatus();
        System.out.println("\n========================================");
        System.out.println("   ESTADO DE LA PETICIÓN");
        System.out.println("========================================");

        if (status != null) {
            System.out.println("Resultado: " + status.getResult());
            System.out.println("Mensaje:   " + (status.getMessage() != null ? status.getMessage() : "N/A"));
            if (status.getCause() != null) {
                System.out.println("Causa:     " + status.getCause());
            }
        }

        System.out.println("\n----------------------------------------");
        System.out.println("   TICKETS GENERADOS");
        System.out.println("----------------------------------------");

        List<BatchTicketItem> tickets = response.getTickets();
        if (tickets == null || tickets.isEmpty()) {
            System.out.println("No se han generado tickets.");
        } else {
            for (BatchTicketItem ticket : tickets) {
                System.out.printf("  > Cliente ID: %-15s | Ticket: %s%n",
                        ticket.getClientId(),
                        ticket.getServerTicketId());
            }
        }
        System.out.println("========================================\n");
    }

    private static void imprimirDetalleGetStatusResponse(GetStatusResponse res) {
        if (res == null) {
            System.out.println("   [!] La respuesta GetStatusResponse es nula.");
            return;
        }

        System.out.println("\n   --- DETALLE DEL TICKET: " + res.getTicketId() + " ---");

        if (res.getStatus() != null) {
            System.out.println("   [ServiceStatus]");
            System.out.println("      Resultado: " + res.getStatus().getResult());
            System.out.println("      Mensaje:   " + res.getStatus().getMessage());
            if (res.getStatus().getCause() != null) {
                System.out.println("      Causa:     " + res.getStatus().getCause());
            }
        } else {
            System.out.println("   [ServiceStatus] Nulo.");
        }

        if (res.getTaskStatus() != null) {
            System.out.println("   [TaskStatus]");
            System.out.println("      Estado del Proceso: " + res.getTaskStatus().getProcessState());
            System.out.println("      Mensaje:            " + res.getTaskStatus().getMessage());
        } else {
            System.out.println("   [TaskStatus] Nulo.");
        }

        if (res.getProcessedDocument() != null) {
            System.out.println("   [ProcessedDocument]");
            System.out.println("      Nombre Fichero: " + res.getProcessedDocument().getFileName());
            System.out.println("      Content Type:   " + res.getProcessedDocument().getContentType());
            byte[] fileContent = res.getProcessedDocument().getFileContent();
            System.out.println("      Tamaño (bytes): " + (fileContent != null ? fileContent.length : 0));
        } else {
            System.out.println("   [ProcessedDocument] Nulo (El documento aún no está listo o hubo error).");
        }

        if (res.getRedactionResults() != null && res.getRedactionResults() != null
                && !res.getRedactionResults().isEmpty()) {
            List<RedactionItem> items = res.getRedactionResults();
            System.out.println("   [Hallazgos Censurados] Total encontrados: " + items.size());

            for (int i = 0; i < items.size(); i++) {
                RedactionItem item = items.get(i);
                System.out.println("      - Item #" + (i + 1) + ":");
                System.out.println("          Tipo:        " + item.getDetectionType());
                System.out.println("          Descripción: " + item.getDescription());
                if (item.getText() != null && !item.getText().isEmpty()) {
                    System.out.println("          Texto:       " + item.getText());
                }
                if (item.getCoordinates() != null) {
                    System.out.println("          Coords:      [X:" + item.getCoordinates().getX() +
                            ", Y:" + item.getCoordinates().getY() +
                            ", W:" + item.getCoordinates().getW() +
                            ", H:" + item.getCoordinates().getH() + "]");
                }
            }
        } else {
            System.out.println("   [Hallazgos Censurados] Nulo o vacío (No se detectaron elementos o no se solicitó).");
        }

        System.out.println("   -------------------------------------------------");
    }

}
