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

package io.github.sensia.processor.model.config;

import java.util.List;

import io.github.sensia.processor.model.dto.CoordinateDTO;

public record DocumentConfig(
    boolean redactQRCodes,
    boolean redactNames,
    boolean redactNERNames,
    boolean redactNEROrg,
    boolean redactNERLoc,
    boolean redactNERMisc,
    double redactNERScore,
    boolean removeImages,
    List<CoordinateDTO> imageExclusionZones,
    List<CoordinateDTO> fixedRedactionZones,
    List<String> allowedWords,           
    List<String> blockedWords,
    int redactionThickness,
    boolean usePseudonyms
) {}