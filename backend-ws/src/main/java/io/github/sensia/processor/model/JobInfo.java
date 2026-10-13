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

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.github.sensia.processor.model.dto.RedactionItemDTO;
import io.github.sensia.processor.model.dto.TaskStatusDTO;

// El PDF se guarda en disco, no en memoria
public class JobInfo {
    private volatile TaskStatusDTO estadoPublico;
    private long timestampCreacion;
    private String fileName;
    private String contentType;
    private File archivoResultado;
    private String documentHash;
    List<RedactionItemDTO> findings;

    public JobInfo(TaskStatusDTO estado, String fileName, String contentType, String documentHash, List<RedactionItemDTO> findings) {
        this.estadoPublico = estado;
        this.fileName = fileName;
        this.contentType = contentType;
        this.documentHash=documentHash;
        this.timestampCreacion = System.currentTimeMillis();
        if (findings==null){
            this.findings = new ArrayList<>();
        }
        else{
            this.findings = findings;
        }
    }

    public TaskStatusDTO getEstadoPublico() {
        return estadoPublico;
    }

    public void setEstadoPublico(TaskStatusDTO estadoPublico) {
        this.estadoPublico = estadoPublico;
    }

    public long getTimestampCreacion() {
        return timestampCreacion;
    }

    public void setTimestampCreacion(long timestampCreacion) {
        this.timestampCreacion = timestampCreacion;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getDocumentHash(){
        return this.documentHash;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public File getArchivoResultado() {
        return archivoResultado;
    }

    public void setArchivoResultado(File archivoResultado) {
        this.archivoResultado = archivoResultado;
    }

    public void setFindings(List<RedactionItemDTO> findings) {
        this.findings = findings;
    }

    public List<RedactionItemDTO> getFindings() {
        return findings;
    }

}