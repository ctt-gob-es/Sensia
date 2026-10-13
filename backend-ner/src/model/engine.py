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

import os
import unicodedata
import re
import threading
import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer, AutoConfig
from typing import List, TypedDict
from src.config.settings import settings
from src.config.logger import setup_logger

logger = setup_logger("ner-engine")


# ---------------------------------------------------------------------------
# Tipos
# ---------------------------------------------------------------------------

class EntityOut(TypedDict):
    word: str
    category: str
    score: float
    start_char: int
    end_char: int


def softmax(x: np.ndarray) -> np.ndarray:
    """Aplica softmax estable sobre el último eje de los logits."""
    e_x = np.exp(x - np.max(x, axis=-1, keepdims=True))
    return e_x / e_x.sum(axis=-1, keepdims=True)


# ---------------------------------------------------------------------------
# Tipos de vía
# ---------------------------------------------------------------------------

# Tipos de vía del Catastro: Servicios web libres v2.6, Anexo II (dic. 2025)
_CODIGOS_CATASTRO = {
    "AC", "AG", "AL", "AN", "AR", "AU", "AV", "AY", "BJ", "BL", "BO", "BQ", "BR", "CA",
    "CG", "CH", "CI", "CJ", "CL", "CM", "CN", "CO", "CP", "CR", "CS", "CT", "CU", "CY",
    "CZ", "DE", "DP", "DS", "ED", "EM", "EN", "EP", "ER", "ES", "EX", "FC", "FN", "GL",
    "GR", "GV", "HT", "JR", "LA", "LD", "LG", "MA", "MC", "ML", "MN", "MS", "MT", "MZ",
    "PB", "PC", "PD", "PI", "PJ", "PL", "PM", "PQ", "PR", "PS", "PT", "PU", "PZ", "QT",
    "RA", "RB", "RC", "RD", "RM", "RP", "RR", "RU", "SA", "SC", "SD", "SL", "SN", "SU",
    "TN", "TO", "TR", "UR", "VA", "VD", "VI", "VL", "VR",
}

# Códigos que en texto libre coinciden con palabras o siglas frecuentes
_CODIGOS_AMBIGUOS = {
    "DE", "LA", "EN", "AL", "SU", "VA", "ES", "TO",   # preposiciones, artículos, verbos
    "SA", "SL", "SC", "AG", "CO", "FC",               # formas societarias y clubes
    "CP", "CA", "VI", "SN",                           # código postal, comunidad autónoma, numeral romano, "sin número"
}

# Solo se reconocen en mayúsculas
CODIGOS_VIA = _CODIGOS_CATASTRO - _CODIGOS_AMBIGUOS

# Denominaciones que casi siempre indican una vía (sin distinguir mayúsculas).
# Se excluyen las del Catastro que son palabras comunes, apellidos o términos de
# organismos: CARMEN, COLEGIO, GRUPO, MERCADO, PARQUE, SECTOR, DIPUTACIÓN,
# MUNICIPIO, VALLE, HUERTA, ARROYO, PUENTE, MONTE, CUESTA, RINCÓN, RIERA...
NOMBRES_VIA = {
    "CALLE", "CALLEJA", "CALLEJÓN", "CALLEJON", "CALLIZO", "CALEYA",
    "AVENIDA", "PLAZA", "PLACETA", "PASEO", "PASAJE", "PASADIZO",
    "CARRETERA", "AUTOPISTA", "AUTOVÍA", "AUTOVIA", "CAMINO", "SENDA", "VEREDA",
    "TRAVESÍA", "TRAVESIA", "RONDA", "GLORIETA", "RAMBLA", "COSTANILLA", "ESCALINATA",
    "URBANIZACIÓN", "URBANIZACION", "POLÍGONO", "POLIGONO", "PARTIDA", "DISEMINADO", "DISEMINADOS",
    "BARRIO", "ARRABAL", "EXTRARRADIO", "EXTRAMUROS", "ENSANCHE", "COLONIA", "CASERÍO", "CASERIO",
    "ALAMEDA", "ANDADOR", "BULEVAR", "RÚA", "RUA", "VIADUCTO", "EXPLANADA", "MALECÓN", "MALECON",
    # valenciano / catalán
    "CARRER", "CARRERÓ", "AVINGUDA", "PLAÇA", "PASSEIG", "PASSATGE", "CAMÍ", "TRAVESSERA",
    "URBANITZACIÓ", "POLÍGON", "BARRI", "RAVAL",
}

# Abreviaturas habituales que no son del Catastro (sin distinguir mayúsculas)
ABREVIATURAS_VIA = {
    "C", "C/", "CL", "AV", "AVD", "AVDA", "AVGDA", "PL", "PZA", "PLZA", "PÇA",
    "PSO", "Pº", "PSG", "CTRA", "CRTA", "CNO", "TRV", "TRAV", "GTA", "RBLA",
    "URB", "POL", "PDA", "PSJE", "Bº",
}

SOLAPE_TOKENS = 128     # tokens compartidos entre ventanas consecutivas
VENTANAS_POR_LOTE = 8   # limita la memoria con textos muy largos


def _es_tipo_via(palabra: str) -> bool:
    """
    Códigos del Catastro (CL, GV, RB...): solo en mayúsculas.
    Nombres y abreviaturas (Calle, Avda., C/, Carrer...): sin distinguir mayúsculas.
    También pegados al nombre: C/ESPRONCEDA, AVDA.CONSTITUCIÓN.
    Las siglas de una letra con punto (C.B., C.F., S.A.) no cuentan.
    Debe recibir la palabra tal como aparece en el texto, sin pasarla a mayúsculas.
    """
    base = palabra.rstrip(".")
    if base in CODIGOS_VIA:
        return True
    if base.upper() in NOMBRES_VIA or base.upper() in ABREVIATURAS_VIA:
        return True
    if palabra.upper().startswith("C/"):
        return True
    if "." in palabra:
        prefijo = palabra.split(".", 1)[0]
        return len(prefijo) >= 2 and (prefijo in CODIGOS_VIA or prefijo.upper() in ABREVIATURAS_VIA)
    return False


# ---------------------------------------------------------------------------
# Postproceso de entidades
# ---------------------------------------------------------------------------

_PALABRA = re.compile(r"\S+")


def fusionar_org_loc(text, entidades):
    """
    Une una ORG con la LOC que la sigue cuando forman un mismo nombre
    ("BANCO BILBAO" + "VIZCAYA ARGENTARIA"), para que el nombre completo
    se trate con el criterio de ORG y no quede tachado a medias.
    No se unen si entre ambas hay algo más que espacios, si la LOC contiene
    dígitos o si empieza por un tipo de vía (en esos casos es una dirección).
    La entidad resultante conserva el score de la ORG.
    """
    resultado = []
    for e in entidades:
        previa = resultado[-1] if resultado else None
        if (previa and previa["category"] == "ORG" and e["category"] == "LOC"
                and text[previa["end_char"]:e["start_char"]].strip() == ""
                and not any(c.isdigit() for c in e["word"])
                and not _es_tipo_via(e["word"].split()[0])):
            previa["end_char"] = e["end_char"]
            previa["word"] = text[previa["start_char"]:previa["end_char"]]
            continue
        resultado.append(dict(e))
    return resultado


def separar_direcciones(text, entidades):
    """
    Si una ORG contiene un tipo de vía ("BANCO SABADELL CL ESPRONCEDA"),
    desde ese punto es una dirección: se separa como LOC. Si la ORG empieza
    por un tipo de vía, toda ella es una dirección. Así la calle no queda
    visible cuando las organizaciones no se censuran.
    """
    resultado = []
    for e in entidades:
        corte = None
        if e["category"] == "ORG":
            palabras = list(_PALABRA.finditer(text, e["start_char"], e["end_char"]))
            corte = next((i for i, p in enumerate(palabras) if _es_tipo_via(p.group())), None)
        if corte is None:
            resultado.append(dict(e))
        elif corte == 0:
            resultado.append({**e, "category": "LOC"})
        else:
            fin_org, ini_dir = palabras[corte - 1].end(), palabras[corte].start()
            resultado.append({**e, "end_char": fin_org, "word": text[e["start_char"]:fin_org]})
            resultado.append({**e, "category": "LOC", "start_char": ini_dir,
                              "word": text[ini_dir:e["end_char"]]})
    return resultado


# ---------------------------------------------------------------------------
# Agregación por palabra
# ---------------------------------------------------------------------------

def _es_de_palabra(c: str) -> bool:
    # Letras, números y marcas combinantes (tildes en NFD)
    return unicodedata.category(c)[0] in ("L", "N", "M")


def unir_ventanas(enc, probs):
    """
    Une ventanas solapadas en una sola secuencia de subtokens. Cada palabra se
    toma de la ventana en la que queda más lejos de los bordes (más contexto).
    """
    ventanas = [enc.word_ids(v) for v in range(probs.shape[0])]
    mejor = {}  # palabra -> (distancia al borde, ventana)
    for v, wids in enumerate(ventanas):
        reales = [j for j, w in enumerate(wids) if w is not None]
        if not reales:
            continue
        ini, fin = reales[0], reales[-1]
        for j in reales:
            d = min(j - ini, fin - j)
            if wids[j] not in mejor or d > mejor[wids[j]][0]:
                mejor[wids[j]] = (d, v)
    tokens = sorted(
        ((int(enc["offset_mapping"][v][j][0]), int(enc["offset_mapping"][v][j][1]), w, probs[v][j])
         for v, wids in enumerate(ventanas)
         for j, w in enumerate(wids)
         if w is not None and mejor[w][1] == v),
        key=lambda t: t[:2])
    return [t[2] for t in tokens], [t[:2] for t in tokens], [t[3] for t in tokens]


def agregar_por_palabra(text, word_ids, offsets, probs, id2label, estrategia="first"):
    """
    Convierte probabilidades por subtoken en entidades a nivel de palabra.
    estrategia: "first" (primer subtoken, por defecto) o "average" (media de los subtokens).
    """
    # Agrupar subtokens por palabra
    palabras = []
    for idx, wid in enumerate(word_ids):
        if wid is None:  # tokens especiales
            continue
        ini, fin = int(offsets[idx][0]), int(offsets[idx][1])
        if palabras and palabras[-1]["wid"] == wid:
            palabras[-1]["fin"] = fin
            palabras[-1]["probs"].append(probs[idx])
        else:
            palabras.append({"wid": wid, "ini": ini, "fin": fin, "probs": [probs[idx]]})

    # Índices de etiqueta por categoría.
    # CAPITEL usa BIOES: B-, I-, E- y S- pertenecen a la misma categoría
    categorias = {}
    for i, etiqueta in id2label.items():
        cat = etiqueta.split("-", 1)[1] if "-" in etiqueta else etiqueta
        categorias.setdefault(cat, []).append(int(i))

    # Categoría por palabra y fusión de palabras contiguas (solo espacios entre ellas)
    entidades = []
    for p in palabras:
        vector = p["probs"][0] if estrategia == "first" else np.mean(p["probs"], axis=0)
        masa = {cat: float(vector[idx].sum()) for cat, idx in categorias.items()}
        cat = max(masa, key=masa.get)
        if cat == "O":
            continue
        previa = entidades[-1] if entidades else None
        if previa and previa["category"] == cat and text[previa["fin"]:p["ini"]].strip() == "":
            previa["fin"] = p["fin"]
            previa["scores"].append(masa[cat])
        else:
            entidades.append({"category": cat, "ini": p["ini"], "fin": p["fin"], "scores": [masa[cat]]})

    # Recortar signos en los extremos
    resultado = []
    for e in entidades:
        ini, fin = e["ini"], e["fin"]
        while ini < fin and not _es_de_palabra(text[ini]):
            ini += 1
        while fin > ini and not _es_de_palabra(text[fin - 1]):
            fin -= 1
        if ini == fin:
            continue
        resultado.append({
            "word": text[ini:fin],
            "category": e["category"],
            "score": round(sum(e["scores"]) / len(e["scores"]), 4),
            "start_char": ini,
            "end_char": fin,
        })
    return resultado


# ---------------------------------------------------------------------------
# Motor
# ---------------------------------------------------------------------------

class NerEngine:
    def __init__(self):
        logger.info("Iniciando motor de inferencia NER de alta precisión (ONNX)...")
        
        # TOKENIZER_PATH debe ser un directorio local;
        # si falta algún fichero, el servicio no arranca en lugar de descargarlo de Internet.
        if not os.path.isdir(settings.TOKENIZER_PATH):
            raise RuntimeError(
                f"TOKENIZER_PATH debe ser un directorio local con el tokenizador y la configuración "
                f"del modelo; valor actual: '{settings.TOKENIZER_PATH}'")

        # Etiquetas del modelo
        try:
            config = AutoConfig.from_pretrained(settings.TOKENIZER_PATH, local_files_only=True)
            self.id2label = config.id2label
            logger.info(f"Cargadas {len(self.id2label)} etiquetas desde la configuración.")
        except Exception as e:
            logger.critical(f"Fallo al cargar config de etiquetas: {e}")
            raise e
        
        # Categorías sin prefijo (B-, I-, E-, S-) que recibirá el motor Java
        esperadas = {"O", "PER", "ORG", "LOC", "OTH"}
        cats = {etq.split("-", 1)[1] if "-" in etq else etq for etq in self.id2label.values()}
        logger.info(f"Etiquetas del modelo: {sorted(self.id2label.values())}")
        logger.info(f"Categorías del modelo: {sorted(cats)}")
        if not cats <= esperadas:
            logger.warning(f"Categorías no esperadas por el motor: {sorted(cats - esperadas)}")

        # Tokenizador
        self.tokenizer = AutoTokenizer.from_pretrained(
            settings.TOKENIZER_PATH,
            add_prefix_space=True,
            local_files_only=True,
        )

        if not self.tokenizer.is_fast:
            raise RuntimeError("Se necesita un tokenizador rápido (word_ids) para agregar por palabra")

        # Tokens por ventana. RoBERTa reserva 2 posiciones: 514 → 512
        self.max_tokens = min(self.tokenizer.model_max_length, config.max_position_embeddings - 2)

        # El tokenizador modifica su estado interno en las primeras llamadas:
        # su uso se serializa con un lock (tokenizar cuesta mucho menos que inferir)
        self._lock_tokenizer = threading.Lock()

        # Opciones del postproceso
        self.estrategia = str(getattr(settings, "NER_AGGREGATION", "first")).strip().lower()
        logger.info(f"Estrategia de agregación NER: {self.estrategia}")
        valor = getattr(settings, "NER_MERGE_ORG_LOC", True)
        self.unir_org_loc = valor if isinstance(valor, bool) else str(valor).strip().lower() in ("1", "true", "yes")
        logger.info(f"Unión ORG + LOC contigua: {self.unir_org_loc}")

        # Sesión ONNX
        sess_options = ort.SessionOptions()
        sess_options.intra_op_num_threads = 2
        sess_options.inter_op_num_threads = 2
        sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        try:
            self.session = ort.InferenceSession(
                settings.MODEL_PATH,
                sess_options=sess_options,
                providers=[settings.ONNX_EXECUTION_PROVIDER]
            )
            logger.info(f"ONNX Session levantada con {settings.ONNX_EXECUTION_PROVIDER}")
        except Exception as e:
            logger.critical(f"No se pudo cargar el modelo ONNX en {settings.MODEL_PATH}: {e}")
            raise e

        # Calentamiento: necesita el tokenizador, el lock y la sesión ya creados.
        # Fija la configuración del tokenizador y hace la primera inferencia (la más lenta).
        # Si falla, el servicio no arranca.
        self._inferir("Calentamiento del motor NER")
        logger.info("Motor NER listo.")


    def _inferir(self, text: str) -> List[EntityOut]:
        # Textos más largos que el modelo: ventanas solapadas en lugar de truncar
        with self._lock_tokenizer:
            enc = self.tokenizer(text, return_tensors="np", truncation=True, padding=True,
                                 max_length=self.max_tokens, stride=SOLAPE_TOKENS,
                                 return_overflowing_tokens=True, return_offsets_mapping=True)
        n = enc["input_ids"].shape[0]
        probs = np.concatenate([
            softmax(self.session.run(None, {
                "input_ids": enc["input_ids"][i:i + VENTANAS_POR_LOTE],
                "attention_mask": enc["attention_mask"][i:i + VENTANAS_POR_LOTE],
            })[0])
            for i in range(0, n, VENTANAS_POR_LOTE)
        ])
        word_ids, offsets, token_probs = unir_ventanas(enc, probs)
        entidades = agregar_por_palabra(text, word_ids, offsets, token_probs, self.id2label, self.estrategia)
        if self.unir_org_loc:
            entidades = fusionar_org_loc(text, entidades)
        return separar_direcciones(text, entidades)

    
    def predict(self, text: str) -> List[EntityOut]:
        if not text.strip():
            return []
        try:
            return self._inferir(text)
        except Exception as e:
            logger.error(f"Error crítico en el pipeline del motor: {e}")
            raise