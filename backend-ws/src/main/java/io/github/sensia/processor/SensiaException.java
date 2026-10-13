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

import io.github.sensia.processor.model.ProcessState;

/** Error con un estado del contrato y un mensaje apto para mostrar al usuario. */
public class SensiaException extends RuntimeException {
    private final ProcessState estado;

    public SensiaException(ProcessState estado, String mensajeUsuario) {
        super(mensajeUsuario);
        this.estado = estado;
    }

    public SensiaException(ProcessState estado, String mensajeUsuario, Throwable causa) {
        super(mensajeUsuario, causa);
        this.estado = estado;
    }

    public ProcessState estado() { return estado; }
}
