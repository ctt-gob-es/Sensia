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

#
# ejecutar con:
# export $(grep -v '^#' .env | xargs) && PYTHONPATH=.:src/protos venv/bin/python scripts/prueba_concurrencia.py
#

import time
from concurrent.futures import ThreadPoolExecutor
from src.model.engine import NerEngine

#
# Datos de prueba sintéticos: nombres frecuentes combinados al azar e identificadores
# de ejemplo. Cualquier coincidencia con personas reales es casual.
#


TEXTOS = [
    "BANCO BILBAO VIZCAYA ARGENTARIA",
    "DON DAVID JESÚS RUBIO GALLEGO Y DOÑA VERÓNICA RUBIO GÓMEZ",
    "Para consultas sobre el IBI, contacte con Juan Pérez al 965 12 34 56.",
    "La inspección técnica se realizó en la nave 4 del Polígono Industrial de Carrús.",
]
HILOS, REPETICIONES = 8, 50

def firma(ents):
    return [(x["word"], x["category"], x["start_char"], x["end_char"]) for x in ents]

e = NerEngine()

def tarea(i):
    t = TEXTOS[i % len(TEXTOS)]
    return t, firma(e.predict(t))

inicio = time.perf_counter()
with ThreadPoolExecutor(max_workers=HILOS) as pool:
    resultados = list(pool.map(tarea, range(HILOS * REPETICIONES)))
duracion = time.perf_counter() - inicio

esperado = {t: firma(e.predict(t)) for t in TEXTOS}   # referencia secuencial
distintos = sum(1 for t, r in resultados if r != esperado[t])
print(f"{len(resultados)} llamadas en {duracion:.1f} s con {HILOS} hilos; resultados distintos: {distintos}")
