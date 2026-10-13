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


package io.github.sensia.processor.model;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.processor.model.dto.ActionStateDTO;
import io.github.sensia.processor.model.dto.CoordinateDTO;
import io.github.sensia.processor.model.dto.RedactionItemDTO;

import java.util.ArrayList;
import java.util.List;

public class RedactionResult implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedactionResult.class);
    
    private final Mat faces;
    private final Mat frame;
    private final String documentHash;

    public RedactionResult(Mat faces, Mat frame, String documentHash) {
        this.faces = faces;
        this.frame = frame;
        this.documentHash=documentHash;
    }

    public List<RedactionItemDTO> getRedactionItems() {
        List<RedactionItemDTO> items = new ArrayList<>();
        
        if (faces == null || faces.empty() || faces.rows() <= 0) {
            return items; 
        }

        float[] data = new float[faces.cols()];
        
        for (int i = 0; i < faces.rows(); i++) {
            faces.get(i, 0, data);

            CoordinateDTO coord = new CoordinateDTO(data[0], data[1], data[2], data[3]);
            
            items.add(new RedactionItemDTO(
                DetectionType.FACE,
                "",
                ActionStateDTO.REDACTED,
                "Rostro detectado por YuNet",
                null, 
                null, 
                coord,
                documentHash,
                true,
                ""
            ));
        }

        return items;
    }

    
    public byte[] getImageData() {
        if (frame == null || frame.empty()) {
            log.warn("Se solicitó getImageData pero el frame original está vacío o es null");
            return new byte[0];
        }

        MatOfByte matOfByte = null;
        try {
            matOfByte = new MatOfByte();
            Imgcodecs.imencode(".jpg", frame, matOfByte);
            return matOfByte.toArray();
        } finally {
            // OJO. Evita una fuga de memoria
            if (matOfByte != null) {
                matOfByte.release();
            }
        }
    }

    @Override
    public void close() {
        if (faces != null) {
            faces.release();
        }
        if (frame != null) {
            frame.release();
        }
    }
}
