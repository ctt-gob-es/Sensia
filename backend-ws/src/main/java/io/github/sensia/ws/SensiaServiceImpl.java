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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.sensia.processor.CensorResources;
import io.github.sensia.processor.ImageUtils;
import io.github.sensia.processor.SensiaException;
import io.github.sensia.processor.img.FaceDetector;
import io.github.sensia.processor.model.ProcessState;
import io.github.sensia.processor.model.RedactionResult;
import io.github.sensia.processor.model.dto.JobResultDTO;
import io.github.sensia.ws.generated.*;

import jakarta.annotation.Resource;
import jakarta.jws.WebService;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.WebServiceException;

import java.util.ArrayList;
import java.util.List;
import org.apache.cxf.annotations.SchemaValidation;

@WebService(name = "SensiaService", endpointInterface = "io.github.sensia.ws.generated.SensiaServicePortType", targetNamespace = "http://sensia.github.io/")
@SchemaValidation
public class SensiaServiceImpl implements SensiaServicePortType {

    @Resource
    private WebServiceContext wsContext;

    private static final Logger log = LoggerFactory.getLogger(SensiaServiceImpl.class);

    @Override
    public SubmitBatchResponse submitBatch(SubmitBatchRequest parameters) {
        log.info("Inicio submitBatch (Asíncrono)");

        if (parameters == null) {
            throw new WebServiceException("La petición SOAP es nula o malformada.");
        }

        verifyIntegritySeal();

        ProcessingOptions options = sanearOpciones(parameters.getOptions());

        List<BatchDocumentItem> documentos = parameters.getDocuments();
        if (documentos == null || documentos.isEmpty()) {
            throw new WebServiceException("Se requiere al menos un elemento en <documentos>.");
        }

        // Un lote mayor no cabría nunca en las plazas del cliente: reintentarlo no serviría de nada
        if (documentos.size() > AsyncJobManager.MAX_POR_CLIENTE) {
            throw new WebServiceException("Un lote admite como máximo " + AsyncJobManager.MAX_POR_CLIENTE
                    + " documentos. Envíe el resto en otras peticiones.");
        }        

        for (int i = 0; i < documentos.size(); i++) {
            BatchDocumentItem doc = documentos.get(i);

            if (doc == null) {
                throw new WebServiceException("El documento en la posición " + i + " es nulo.");
            }

            if (doc.getDocument().getFileName() == null || doc.getDocument().getFileName().trim().isEmpty()) {
                throw new WebServiceException("Todos los documentos deben incluir una etiqueta <fileName> válida.");
            }

            if (doc.getDocument().getContentType() == null || doc.getDocument().getContentType().trim().isEmpty()) {
                throw new WebServiceException(
                        "El documento con ID '" + doc.getDocument().getFileName()
                                + "' no indica <contentType>.");
            }

            if (doc.getDocument().getFileContent() == null || doc.getDocument().getFileContent().length == 0) {
                throw new WebServiceException(
                        "El documento con ID '" + doc.getDocument().getFileName()
                                + "' no contiene base64 en <fileContent>.");
            }

            if (doc.getConfirmedRedactions()!=null && doc.getConfirmedRedactions().size()>0 && !Boolean.TRUE.equals(options.isReconstructionMode())){
                throw new WebServiceException(
                        "Se ha indicado lista de redacciones pero no se ha indicado isReconstructionMode=true ' en fichero: " + doc.getDocument().getFileName());
            }
        }

        SubmitBatchResponse response = new SubmitBatchResponse();
        ServiceStatus status = new ServiceStatus();

        try {
            CensorResources recursos = CensorResources.getInstance();
            AsyncJobManager manager = AsyncJobManager.getInstance();
            List<BatchTicketItem> ticketsGenerados = new ArrayList<BatchTicketItem>();
            // Clave del usuario final que deja AuthTokenInterceptor
            String cliente = (String) wsContext.getMessageContext().get(AuthTokenInterceptor.CLIENTE);
            
            // Comprobación y encolado atómicos: dentro del bloque las plazas solo pueden aumentar.
            // Se comprueba el lote entero para no encolar una parte y perder sus tickets

            synchronized (manager) {
                if (documentos.size() > manager.plazasLibres()) {
                    throw new SensiaException(ProcessState.SATURADO, "Servicio saturado: demasiados documentos en cola. Reintente más tarde.");
                }
                if (documentos.size() > manager.plazasLibres(cliente)) {
                    throw new SensiaException(ProcessState.SATURADO, "Servicio saturado: demasiados documentos en cola. Reintente más tarde. cliente="+cliente);
                }
                for (BatchDocumentItem doc : parameters.getDocuments()) {
                    String ticketId = manager.encolarTrabajo(SensiaMapper.mapToDocumentDTO (doc), 
                                                             SensiaMapper.mapToInternalContext(options), recursos, cliente);
                    BatchTicketItem ticketItem = new BatchTicketItem();
                    ticketItem.setClientId(doc.getClientId());
                    ticketItem.setServerTicketId(ticketId);
                    ticketsGenerados.add(ticketItem);
                }
            }            

            response.getTickets().addAll(ticketsGenerados);
            status.setResult(StatusEnum.SUCCESS);
            status.setMessage("Operación finalizada con éxito. Lote encolado. Tickets generados.");
            response.setStatus(status);

            log.info("Lote encolado. Tickets generados: " + ticketsGenerados.size());

        } catch (SensiaException e) {
            // Si cause lleva el estado: SATURADO indica que se puede reintentar más tarde
            log.warn("Lote rechazado ({}): {}", e.estado(), e.getMessage());
            status.setResult(StatusEnum.ERROR);
            status.setMessage(e.getMessage());
            status.setCause(e.estado().name());
            response.setStatus(status);        
        } 
        catch (Exception e) {
            log.error("Error encolando lote", e);
            status.setResult(StatusEnum.ERROR);
            status.setMessage(e.getMessage());
            if (e.getCause() != null)
                status.setCause(e.getCause().getMessage());
            response.setStatus(status);
        }

        return response;
    }

    @Override
    public GetStatusResponse getStatus(GetStatusRequest parameters) {

        log.info("Inicio consultarEstado");

        if (parameters == null) {
            throw new WebServiceException("La petición SOAP es nula o malformada.");
        }

        verifyIntegritySeal();

        if (parameters.getTicketId() == null || parameters.getTicketId().trim().isEmpty()) {
            GetStatusResponse response = new GetStatusResponse();
            ServiceStatus status = new ServiceStatus();
            response.setTicketId("-");
            status.setResult(StatusEnum.ERROR);
            status.setMessage("El ticketId es obligatorio.");
            response.setStatus(status);
            return response;
        }

        try {
            String ticketId = parameters.getTicketId();

            JobResultDTO resultadoTarea = AsyncJobManager.getInstance().consultarYBorrar(ticketId);

            if (resultadoTarea == null) {
                GetStatusResponse response = new GetStatusResponse();
                response.setTicketId(parameters.getTicketId());
                TaskStatus taskStatus = new TaskStatus();
                taskStatus.setProcessState(ProcessStateEnum.NOT_FOUND);
                String mensaje = String.format("El ticket %s no existe o ha caducado.", ticketId);
                taskStatus.setMessage(mensaje);
                log.info(mensaje);
                response.setTaskStatus(taskStatus);
                ServiceStatus status = new ServiceStatus();
                status.setResult(StatusEnum.SUCCESS);
                status.setMessage("Operación finalizada con éxito.");
                response.setStatus(status);
                return response;
            } else {
                ServiceStatus status = new ServiceStatus();
                status.setResult(StatusEnum.SUCCESS);
                status.setMessage("Operación finalizada con éxito.");
                GetStatusResponse response = SensiaMapper.mapToGetStatusResponse(ticketId, resultadoTarea, status);
                return response;
            }
        } catch (Exception e) {
            GetStatusResponse response = new GetStatusResponse();
            log.error("Error consultando el estado", e);
            ServiceStatus status = new ServiceStatus();
            status.setResult(StatusEnum.ERROR);
            status.setMessage(e.getMessage());
            if (e.getCause() != null)
                status.setCause(e.getCause().getMessage());
            response.setTicketId(parameters.getTicketId());
            response.setStatus(status);
            return response;
        }

    }

    @Override
    public RedactImageResponse redactImage(RedactImageRequest parameters) {

        log.info("Inicio redactImage");

        if (parameters == null) {
            throw new WebServiceException("La petición SOAP es nula o malformada.");
        }

        verifyIntegritySeal();
        
        FaceMaskingOptions faceMaskingOpts = sanearOpcionesFaceMasking(parameters.getFaceOptions());

        YunetOptions yunetOpts = sanearOpcionesYunet(parameters.getYunetOptions());

        RedactImageRequest safe =(parameters != null) ? parameters : new RedactImageRequest();
        safe.setFaceOptions(faceMaskingOpts);
        safe.setYunetOptions(yunetOpts);
        if (safe.isRedactionPreview() == null){
            ConfigManager settings = ConfigManager.getInstance("app.properties");
            safe.setRedactionPreview(settings.getBooleanConfig("processing.redactionPreview", false));
        }

        log.debug("redactImage ------ 1");

        // Obtener la instancia del detector
        FaceDetector detector = FaceDetector.getFaceDetector();

        if (detector == null) {
            log.error("Error: FaceDetectorYN no está inicializado.");
            throw new RuntimeException("Error: FaceDetectorYN no está inicializado.");
        }

        log.debug("redactImage ------ 2");
        
        try (RedactionResult redactionResult = detector.detectFaces(safe.getImage().getFileContent(),
                    SensiaMapper.mapToYunetConfig(yunetOpts),
                    SensiaMapper.mapToFaceMaskingConfig(faceMaskingOpts),
                    safe.isRedactionPreview())) {

            log.debug("processImage  ------ 4");

            ServiceStatus status = new ServiceStatus();
            status.setResult(StatusEnum.SUCCESS);
            status.setMessage("Operación finalizada con éxito.");
            RedactImageResponse response = SensiaMapper.mapToRedactImageResponse(safe.getImage().getFileName(),
                    "image/jpeg",
                    redactionResult, status);

            log.debug("processImage ----- 5");
            return response;
        } catch (Exception e) {
            RedactImageResponse response = new RedactImageResponse();
            ServiceStatus status = new ServiceStatus();
            log.debug("redactImage ------ 3");
            log.error("Error detectFaces", e);
            status.setResult(StatusEnum.ERROR);
            status.setMessage(e.getMessage());
            if (e.getCause() != null)
                status.setCause(e.getCause().getMessage());
            response.setStatus(status);
            return response;
        }

    }

    @Override
    public ExtractImagesResponse extractImages(ExtractImagesRequest parameters) {
        log.info("extractImages. inicio");

        if (parameters == null) {
            throw new WebServiceException("La petición SOAP es nula o malformada.");
        }

        verifyIntegritySeal();

        ExtractImagesResponse response = new ExtractImagesResponse();
        ServiceStatus status = new ServiceStatus();

        double minH = (parameters.getMinHeight() != null) ? parameters.getMinHeight() : 0;
        double minW = (parameters.getMinWidth() != null) ? parameters.getMinWidth() : 0;
        double maxH = (parameters.getMaxHeight() != null) ? parameters.getMaxHeight() : Double.MAX_VALUE;
        double maxW = (parameters.getMaxWidth() != null) ? parameters.getMaxWidth() : Double.MAX_VALUE;
        
        try {
            DocumentList images = ImageUtils.extractImages(parameters.getDocument().getFileName(),
                    parameters.getDocument().getContentType(),
                    parameters.getDocument().getFileContent(), minH, minW, maxH, maxW);
            response.setImages(images);
            status.setResult(StatusEnum.SUCCESS);
            status.setMessage("Operación finalizada con éxito.");
            response.setStatus(status);

        } catch (Exception e) {
            log.debug("extractImages ------ 3");
            log.error("Error extractImages", e);
            status.setResult(StatusEnum.ERROR);
            status.setMessage(e.getMessage());
            if (e.getCause() != null)
                status.setCause(e.getCause().getMessage());
            response.setStatus(status);
        }
        return response;

    }

    /**
     * Comprueba que el mensaje ha pasado por los interceptores de autenticación.
     */
    private void verifyIntegritySeal() {
        if (wsContext == null || wsContext.getMessageContext() == null) {
            throw new WebServiceException("Acceso denegado: Contexto no disponible.");
        }

        String receivedSeal = (String) wsContext.getMessageContext().get("SENSIA_INTERNAL_SEAL");

        String expectedSeal = SecuritySealGenerator.getValidSeal();

        if (receivedSeal == null || !expectedSeal.equals(receivedSeal)) {
            log.error("ERROR DE SEGURIDAD: Sello no encontrado o inválido en el contexto de aplicación.");
            throw new WebServiceException("Acceso denegado. Verificación de integridad fallida.");
        }
    }

    public YunetOptions sanearOpcionesYunet(YunetOptions raw) {
        if (raw != null) {
            return raw;
        }
        ConfigManager settings = ConfigManager.getInstance("app.properties");
        YunetOptions yunetOpts = new YunetOptions();
        yunetOpts.setScoreThreshold(settings.getFloatConfig("processing.yunet.scoreThreshold", 0.30f));
        yunetOpts.setNmsThreshold(settings.getFloatConfig("processing.yunet.nmsThreshold", 0.45f));
        yunetOpts.setTopK(settings.getIntConfig("processing.yunet.topK", 5000));
        return yunetOpts;
    }

    public FaceMaskingOptions sanearOpcionesFaceMasking(FaceMaskingOptions raw) {
        ConfigManager settings = ConfigManager.getInstance("app.properties");

        FaceMaskingOptions faceOpts = (raw != null) ? raw : new FaceMaskingOptions();

        if (faceOpts.isMaskFaces() == null)
            faceOpts.setMaskFaces(settings.getBooleanConfig("processing.face.maskFaces", true));
        if (faceOpts.getPixelationIntensity() == null)
            faceOpts.setPixelationIntensity(settings.getIntConfig("processing.face.pixelationIntensity", 70));
        if (faceOpts.getMinFaceAspectRatio() == null)
            faceOpts.setMinFaceAspectRatio(settings.getFloatConfig("processing.face.minFaceAspectRatio", 0.0f));
        if (faceOpts.getMaxFaceAspectRatio() == null)
            faceOpts.setMaxFaceAspectRatio(settings.getFloatConfig("processing.face.maxFaceAspectRatio", 0.0f));
        if (faceOpts.getMinFaceArea() == null)
            faceOpts.setMinFaceArea(settings.getFloatConfig("processing.face.minFaceArea", 0.0f));
        if (faceOpts.getMaxFaceArea() == null)
            faceOpts.setMaxFaceArea(settings.getFloatConfig("processing.face.maxFaceArea", 0.0f));

        return faceOpts;
    }

    public ProcessingOptions sanearOpciones(ProcessingOptions raw) {
        ConfigManager settings = ConfigManager.getInstance("app.properties");

        ProcessingOptions safe = (raw != null) ? raw : new ProcessingOptions();
        if (safe.getDocumentOptions() == null) {
            safe.setDocumentOptions(new DocumentRedactionOptions());
        }

        if (safe.isRedactionPreview() == null)
            safe.setRedactionPreview(settings.getBooleanConfig("processing.redactionPreview", false));

        DocumentRedactionOptions docOpts = safe.getDocumentOptions();

        if (docOpts.isRedactQRCodes() == null)
            docOpts.setRedactQRCodes(settings.getBooleanConfig("processing.document.redactQRCodes", false));
        if (docOpts.isRedactNames() == null)
            docOpts.setRedactNames(settings.getBooleanConfig("processing.document.redactNames", false));
        if (docOpts.isRedactNERNames() == null)
            docOpts.setRedactNERNames(settings.getBooleanConfig("processing.document.redactNERNames", false));
        if (docOpts.isRedactNEROrg() == null)
            docOpts.setRedactNEROrg(settings.getBooleanConfig("processing.document.redactNEROrg", false));
        if (docOpts.isRedactNERLoc() == null)
            docOpts.setRedactNERLoc(settings.getBooleanConfig("processing.document.redactNERLoc", false));
        if (docOpts.isRedactNERMisc() == null)
            docOpts.setRedactNERMisc(settings.getBooleanConfig("processing.document.redactNERMisc", false));
        if (docOpts.getRedactNERScore() == null)
            docOpts.setRedactNERScore(settings.getFloatConfig("processing.document.redactNERScore", 0.65f));
        if (docOpts.isRemoveImages() == null)
            docOpts.setRemoveImages(settings.getBooleanConfig("processing.document.removeImages", false));
        if (docOpts.isPreserveLogo() == null)
            docOpts.setPreserveLogo(settings.getBooleanConfig("processing.document.preserveLogo", true));
        if (docOpts.getRedactionThickness() == null)
            docOpts.setRedactionThickness(settings.getIntConfig("processing.document.redactionThickness", 2));
        if (docOpts.isUsePseudonyms() == null)
            docOpts.setUsePseudonyms(settings.getBooleanConfig("processing.document.usePseudonyms", false));
        
        
        if (docOpts.isPreserveLogo()) {
            Coordinate coordLogo = docOpts.getLogoExclusionZone();
            if (coordLogo == null) {
                float[] coordLogoDefault = { 0f, 700f, 200f, 142 };
                float[] rectLogo = settings.getRectConfig("processing.document.logoExclusionZone", coordLogoDefault);
                coordLogo = new Coordinate();
                coordLogo.setX(rectLogo[0]); coordLogo.setY(rectLogo[1]);
                coordLogo.setW(rectLogo[2]); coordLogo.setH(rectLogo[3]);
                docOpts.setLogoExclusionZone(coordLogo);
            }
            if (docOpts.getImageExclusionZones() == null) {
                docOpts.setImageExclusionZones(new CoordinatesList());
            }
            docOpts.getImageExclusionZones().getCoordinate().add(coordLogo);
        }

        FaceMaskingOptions faceOpts = sanearOpcionesFaceMasking(safe.getFaceOptions());
        safe.setFaceOptions(faceOpts);

        YunetOptions yunetOpts = sanearOpcionesYunet(safe.getYunetOptions());
        safe.setYunetOptions(yunetOpts);

        return safe;
    }

}
