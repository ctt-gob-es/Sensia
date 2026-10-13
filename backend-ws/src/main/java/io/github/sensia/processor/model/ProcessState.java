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

public enum ProcessState {
    QUEUED, PROCESSING, COMPLETED, SATURADO, NOT_FOUND, AUTH_ERROR, INVALID_DOCUMENT_ERROR, HASH_MISMATCH_ERROR, ERROR ;

    public boolean esFinal() {
        return this != QUEUED && this != PROCESSING;
    }
}