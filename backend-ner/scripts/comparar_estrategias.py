"""
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 
 """

from src.model.engine import NerEngine

#
# Datos de prueba sintéticos: nombres frecuentes combinados al azar e identificadores
# de ejemplo. Cualquier coincidencia con personas reales es casual.
#

TEXTOS = [
    "BANCO BILBAO VIZCAYA ARGENTARIA",
    "CL ESPRONCEDA 12 ELCHE",
    "DON DAVID JESÚS RUBIO GALLEGO Y DOÑA VERÓNICA RUBIO GÓMEZ",
    "Dña. María Gómez y su cónyuge, D. Carlos Rodríguez, declaran la venta de la finca urbana.",
]

e = NerEngine()

# Trozos en los que el modelo parte el texto (Ġ marca el inicio de palabra)
print(e.tokenizer.tokenize("BANCO BILBAO VIZCAYA ARGENTARIA"))

for estrategia in ("average", "first"):
    e.estrategia = estrategia
    print(f"\n== {estrategia}")
    for t in TEXTOS:
        print(t, "->", [(x["word"], x["category"], x["score"]) for x in e.predict(t)])
