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

package io.github.sensia.processor.pdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.text.Normalizer;

import org.junit.Test;

public class NerSpanUtilsTest {

    private static String ajustar(String texto, String fragmento) {
        int start = texto.indexOf(fragmento);
        int[] span = NerSpanUtils.ajustarALimitesDePalabra(texto, start, start + fragmento.length());
        return texto.substring(span[0], span[1]);
    }

    @Test
    public void subtokenSeAmpliaAPalabra() {
        assertEquals("SABADELL", ajustar("BANCO SABADELL, S.A.", "SABA"));
        assertEquals("HERNÁNDEZ", ajustar("D. JUAN HERNÁNDEZ", "NÁNDEZ"));
    }

    @Test
    public void espacioInicialNoArrastraLaPalabraAnterior() {
        assertEquals("BILBAO", ajustar("BANCO BILBAO VIZCAYA", " BILBAO"));
    }

    @Test
    public void apellidoCompuestoConGuion() {
        assertEquals("GARCÍA-LÓPEZ", ajustar("Sra. GARCÍA-LÓPEZ", "LÓPEZ"));
    }

    @Test
    public void tildeDescompuesta() {
        String nfd = Normalizer.normalize("HERNÁNDEZ", Normalizer.Form.NFD);
        assertEquals(nfd, ajustar("D. " + nfd, "NDEZ"));
    }

    @Test
    public void letraDeSelloQuedaBajoElMinimo() {
        String sello = "E S C O P I A";
        int[] span = NerSpanUtils.ajustarALimitesDePalabra(sello, 2, 3);
        String texto = sello.substring(span[0], span[1]);
        assertEquals("S", texto);
        assertTrue(NerSpanUtils.contarAlfanumericos(texto) < 3);
    }

    @Test
    public void spanSoloPuntuacionQuedaVacio() {
        int[] span = NerSpanUtils.ajustarALimitesDePalabra("A, B", 1, 3);
        assertEquals(span[0], span[1]);
    }

        @Test
    public void fragmentosDelLog() {
        assertEquals("Caixabank", ajustar("Caixabank, Sabadell", "ixabank"));
        assertEquals("GALLEGO", ajustar("RUBIO GALLEGO Y", "GALLE"));
        assertEquals("ESPRONCEDA", ajustar("CL ESPRONCEDA 12", "ONCED"));
        assertEquals("D´ELX", ajustar("AJUNTAMENT D´ELX", "ELX"));
    }

    @Test
    public void puntuacionEnExtremosSeRecorta() {
        assertEquals("Tributària", ajustar("Oficina de Suma - Gestió Tributària*", "Tributària*"));
        assertEquals("Reglamento", ajustar("Reglamento (UE)", "Reglamento ("));
    }

    @Test
    public void decodificacionIndiceUtf16() {
        assertEquals(3, NerSpanUtils.aIndiceUtf16("\uD835\uDC00 Ana", 2)); // U+1D400 ocupa 2 unidades UTF-16
    }
}