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

import java.util.UUID;

public class SecuritySealGenerator {
    
    // UUID aleatorio generado al arrancar: al reiniciar el servidor, el sello cambia.
    private static final String INTERNAL_SEAL = UUID.randomUUID().toString();

    private SecuritySealGenerator() {
    }

    public static String getValidSeal() {
        return INTERNAL_SEAL;
    }
}
