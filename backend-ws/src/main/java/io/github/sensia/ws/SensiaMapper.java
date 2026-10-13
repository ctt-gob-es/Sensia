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

import java.util.ArrayList;
import java.util.List;

import io.github.sensia.processor.model.ProcessState;
import io.github.sensia.processor.model.RedactionResult;
import io.github.sensia.processor.model.config.DocumentConfig;
import io.github.sensia.processor.model.config.FaceMaskingConfig;
import io.github.sensia.processor.model.config.PdfProcessorContext;
import io.github.sensia.processor.model.config.YunetConfig;
import io.github.sensia.processor.model.dto.CoordinateDTO;
import io.github.sensia.processor.model.dto.DocumentDTO;
import io.github.sensia.processor.model.dto.JobResultDTO;
import io.github.sensia.processor.model.dto.RedactionItemDTO;
import io.github.sensia.ws.generated.GetStatusResponse;
import io.github.sensia.ws.generated.ProcessStateEnum;
import io.github.sensia.ws.generated.TaskStatus;
import io.github.sensia.ws.generated.ActionStateEnum;
import io.github.sensia.ws.generated.BatchDocumentItem;
import io.github.sensia.ws.generated.Coordinate;
import io.github.sensia.ws.generated.DetectionTypeEnum;
import io.github.sensia.ws.generated.DocumentItem;
import io.github.sensia.ws.generated.DocumentRedactionOptions;
import io.github.sensia.ws.generated.FaceMaskingOptions;
import io.github.sensia.ws.generated.ProcessingOptions;
import io.github.sensia.ws.generated.RedactImageResponse;
import io.github.sensia.ws.generated.RedactionItem;
import io.github.sensia.ws.generated.ServiceStatus;
import io.github.sensia.ws.generated.YunetOptions;

public class SensiaMapper {

    public static PdfProcessorContext mapToInternalContext(ProcessingOptions wsOptions) {

        YunetConfig yConfig = mapToYunetConfig(wsOptions.getYunetOptions());
        FaceMaskingConfig fConfig = mapToFaceMaskingConfig(wsOptions.getFaceOptions());
        DocumentConfig dConfig = mapToDocumentConfig(wsOptions.getDocumentOptions());

        boolean isPreview = wsOptions.isRedactionPreview() != null ? wsOptions.isRedactionPreview() : false;
        
        boolean isReconstruction = wsOptions.isReconstructionMode() != null ? wsOptions.isReconstructionMode() : false;
        
        return new PdfProcessorContext(dConfig, yConfig, fConfig, isPreview, isReconstruction);
    }

    public static YunetConfig mapToYunetConfig(YunetOptions wsYunetOptions) {

        return new YunetConfig(
                wsYunetOptions.getScoreThreshold(),
                wsYunetOptions.getNmsThreshold(),
                wsYunetOptions.getTopK());
    }

    public static FaceMaskingConfig mapToFaceMaskingConfig(FaceMaskingOptions wsFaceOptions) {

        return new FaceMaskingConfig(
                wsFaceOptions.isMaskFaces(),
                wsFaceOptions.getMinFaceAspectRatio(),
                wsFaceOptions.getMaxFaceAspectRatio(),
                wsFaceOptions.getMinFaceArea(),
                wsFaceOptions.getMaxFaceArea(),
                wsFaceOptions.getPixelationIntensity());
    }

    public static DocumentConfig mapToDocumentConfig(DocumentRedactionOptions wsDocOptions) {

        List<CoordinateDTO> excZones = new ArrayList<>();
        if (wsDocOptions.getImageExclusionZones() != null
                && wsDocOptions.getImageExclusionZones().getCoordinate() != null) {
            for (Coordinate wsCoord : wsDocOptions.getImageExclusionZones().getCoordinate()) {
                excZones.add(new CoordinateDTO(wsCoord.getX(), wsCoord.getY(), wsCoord.getW(), wsCoord.getH()));
            }
        }

        List<CoordinateDTO> fixedZones = new ArrayList<>();
        if (wsDocOptions.getFixedRedactionZones() != null
                && wsDocOptions.getFixedRedactionZones().getCoordinate() != null) {
            for (Coordinate wsCoord : wsDocOptions.getFixedRedactionZones().getCoordinate()) {
                fixedZones.add(new CoordinateDTO(wsCoord.getX(), wsCoord.getY(), wsCoord.getW(), wsCoord.getH()));
            }
        }

        List<String> allowed = (wsDocOptions.getAllowedWords() != null
                && wsDocOptions.getAllowedWords().getWords() != null)
                        ? wsDocOptions.getAllowedWords().getWords()
                        : new ArrayList<>();

        List<String> blocked = (wsDocOptions.getBlockedWords() != null
                && wsDocOptions.getBlockedWords().getWords() != null)
                        ? wsDocOptions.getBlockedWords().getWords()
                        : new ArrayList<>();

        int redactionThickness = wsDocOptions.getRedactionThickness() != null ? wsDocOptions.getRedactionThickness() : 2;
        boolean usePseudonyms = wsDocOptions.isUsePseudonyms() != null ? wsDocOptions.isUsePseudonyms() : false;

        return new DocumentConfig(
                wsDocOptions.isRedactQRCodes(),
                wsDocOptions.isRedactNames(),
                wsDocOptions.isRedactNERNames(),
                wsDocOptions.isRedactNEROrg(),
                wsDocOptions.isRedactNERLoc(),
                wsDocOptions.isRedactNERMisc(),
                wsDocOptions.getRedactNERScore(),
                wsDocOptions.isRemoveImages(),
                excZones,
                fixedZones,
                allowed,
                blocked,
                redactionThickness,
                usePseudonyms
                );
    }

    public static DocumentDTO mapToDocumentDTO(BatchDocumentItem item) {


        List<RedactionItemDTO> censuras = mapToRedactionItemDTOList(item.getConfirmedRedactions());

            
            DocumentDTO dto = new DocumentDTO(
                    item.getDocument().getFileName(),
                    item.getDocument().getContentType(),
                    item.getDocument().getFileContent(), 
                    censuras);


        return dto;
    }

    public static ProcessStateEnum mapToWsProcessState(ProcessState internalState) {
        return io.github.sensia.ws.generated.ProcessStateEnum.valueOf(internalState.name());
    }

    public static GetStatusResponse mapToGetStatusResponse(String ticketId, JobResultDTO jobResult, ServiceStatus status) {

        GetStatusResponse response = new GetStatusResponse();

        response.setTicketId(ticketId);

        TaskStatus taskStatus = new TaskStatus();
        taskStatus.setProcessState(mapToWsProcessState(jobResult.taskStatus().getProcessState()));
        taskStatus.setMessage(jobResult.taskStatus().getMessage());

        response.setTaskStatus(taskStatus);

        if (jobResult.fileContent() != null) {
            DocumentItem docItem = new DocumentItem();
            docItem.setFileName(jobResult.fileName());
            docItem.setContentType(jobResult.contentType());
            docItem.setFileContent(jobResult.fileContent());

            response.setProcessedDocument(docItem);
        }

       
        List<RedactionItemDTO> redactions = jobResult.findings();
        if (redactions != null && !redactions.isEmpty()) {

            for (RedactionItemDTO itemDTO : redactions) {
                RedactionItem item = new RedactionItem();
                item.setDetectionType(DetectionTypeEnum.valueOf(itemDTO.detectionType().name()));
                item.setSubType(itemDTO.subType());
                item.setAction(ActionStateEnum.valueOf(itemDTO.action().name()));
                item.setDescription(itemDTO.description());
                item.setText(itemDTO.text());
                item.setPage(itemDTO.page());
                item.setSourceDocumentHash(itemDTO.sourceDocumentHash());
                item.setApply(itemDTO.apply());
                item.setPseudonym(itemDTO.pseudonym());

                Coordinate wsCoord = new Coordinate();
                wsCoord.setX(itemDTO.coordinates().x());
                wsCoord.setY(itemDTO.coordinates().y());
                wsCoord.setW(itemDTO.coordinates().w());
                wsCoord.setH(itemDTO.coordinates().h());
                item.setCoordinates(wsCoord);

                response.getRedactionResults().add(item);
            }

        }
        response.setStatus(status);
        return response;
    }



    public static RedactImageResponse mapToRedactImageResponse(String fileName, String contentType, 
            RedactionResult redactionResult, ServiceStatus status) {

        RedactImageResponse response = new RedactImageResponse();

        if (redactionResult.getImageData() != null) {
            DocumentItem docItem = new DocumentItem();
            docItem.setFileName(fileName);
            docItem.setContentType(contentType);
            docItem.setFileContent(redactionResult.getImageData());
            response.setImage(docItem);
        }

        List<RedactionItemDTO> redactions = redactionResult.getRedactionItems();
        if (redactions != null && !redactions.isEmpty()) {

            for (RedactionItemDTO itemDTO : redactions) {
                RedactionItem item = new RedactionItem();
                item.setDetectionType(DetectionTypeEnum.valueOf(itemDTO.detectionType().name()));
                item.setAction(ActionStateEnum.valueOf(itemDTO.action().name()));
                item.setDescription(itemDTO.description());
                item.setText(itemDTO.text());
                item.setPage(itemDTO.page());
                item.setSourceDocumentHash(itemDTO.sourceDocumentHash());
                item.setApply(itemDTO.apply());

                Coordinate wsCoord = new Coordinate();
                wsCoord.setX(itemDTO.coordinates().x());
                wsCoord.setY(itemDTO.coordinates().y());
                wsCoord.setW(itemDTO.coordinates().w());
                wsCoord.setH(itemDTO.coordinates().h());
                item.setCoordinates(wsCoord);

                response.getRedactionResults().add(item);
            }

        }
        response.setStatus(status);

        return response;
    }

    public static List<RedactionItemDTO> mapToRedactionItemDTOList(List<RedactionItem> wsItems) {
        List<RedactionItemDTO> resultList = new ArrayList<>();
        if (wsItems == null || wsItems.isEmpty()) return resultList;
        
        for (RedactionItem wsItem : wsItems) {
            CoordinateDTO coord = wsItem.getCoordinates() != null ? new CoordinateDTO(
                    wsItem.getCoordinates().getX(), wsItem.getCoordinates().getY(),
                    wsItem.getCoordinates().getW(), wsItem.getCoordinates().getH()) : null;
            String subType = wsItem.getSubType();
            String sourceHash = wsItem.getSourceDocumentHash();
            boolean apply = wsItem.isApply() != null ? wsItem.isApply() : true;
            String pseudoning = wsItem.getPseudonym() != null ? wsItem.getPseudonym() : "";

            resultList.add(new RedactionItemDTO(
                    io.github.sensia.processor.model.DetectionType.valueOf(wsItem.getDetectionType().name()),
                    subType,
                    io.github.sensia.processor.model.dto.ActionStateDTO.valueOf(wsItem.getAction().name()),
                    wsItem.getDescription(), wsItem.getText(),
                    wsItem.getPage() != null ? wsItem.getPage() : 1, 
                    coord, 
                    sourceHash,
                    apply,
                    pseudoning
                ));
        }
        return resultList;
    }

}
