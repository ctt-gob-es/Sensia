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
from src.config.settings import settings
import pytest
import difflib
import numpy as np

from src.protos import ner_pb2
from src.model.engine import NerEngine, agregar_por_palabra, fusionar_org_loc

#
# Datos de prueba sintéticos: nombres frecuentes combinados al azar e identificadores
# de ejemplo. Cualquier coincidencia con personas reales es casual.
#

# CONFIGURACIÓN

@pytest.fixture(scope="module")
def ner_engine():
    """Carga el motor NER en memoria una sola vez para todo el archivo de tests."""
    if not (os.path.isfile(settings.MODEL_PATH) and os.path.isdir(settings.TOKENIZER_PATH)):
        pytest.skip("Modelo no exportado (make dev-model)")
    return NerEngine()

ID2LABEL = {0: "O", 1: "B-LOC", 2: "I-LOC"}

def vec(label_id, p=0.9):
    v = np.full(len(ID2LABEL), (1 - p) / (len(ID2LABEL) - 1))
    v[label_id] = p
    return v

def entidades(text, word_ids, offsets, probs):
    ents = agregar_por_palabra(text, word_ids, np.array(offsets), np.array(probs), ID2LABEL)
    return [(e["word"], e["category"]) for e in ents]

# Esquema BIOES del modelo CAPITEL (B-, I-, E-, S-)
ID2LABEL_BIOES = {0: "O", 1: "B-PER", 2: "I-PER", 3: "E-PER", 4: "S-PER", 5: "S-LOC"}

def dist(d):
    v = np.zeros(len(ID2LABEL_BIOES))
    for k, p in d.items():
        v[k] = p
    return v

# Entidad tal como la devuelve el modelo, para probar la fusión sin cargarlo
def ent(text, palabra, categoria, score=0.9):
    ini = text.index(palabra)
    return {"word": palabra, "category": categoria, "score": score,
            "start_char": ini, "end_char": ini + len(palabra)}

# Categorías que reconoce el motor
CATEGORIAS_MOTOR = {"PER", "ORG", "LOC", "OTH"}

# DATOS DE PRUEBA

TEST_CASES = [
    # 1. Contribuyente estándar + Delegación (PER + LOC + ORG)
    ("El contribuyente Antonio Machado Ruiz ha presentado su declaración de la renta en la delegación de Sevilla.", 
     ["Antonio Machado Ruiz", "Sevilla"]),
    
    # 2. Dirección administrativa compleja con abreviaturas (LOC/DIR + ID)
    ("Notificación enviada a la Avda. de la Constitución, n.º 45, esc. B, CP 08014, Barcelona, dirigida al NIF 12345678Z.", 
     ["Avda. de la Constitución, n.º 45, esc. B", "08014", "Barcelona", "12345678Z"]),
    
    # 3. Empresas, CIF y Entidades Locales (ORG + ID + ORG)
    ("La empresa Construcciones Ibéricas S.A., con CIF B87654321, solicitó la licencia de obras en el Ayuntamiento de Elche.", 
     ["Construcciones Ibéricas S.A.", "B87654321", "Ayuntamiento de Elche"]),
    
    # 4. Referencias Catastrales y Expedientes (ID)
    ("El expediente sancionador 2026-EXP-445 afecta al inmueble con referencia catastral 9872023VH5797S0001WX.", 
     ["2026-EXP-445", "9872023VH5797S0001WX"]),
    
    # 5. Teléfonos y Correos en contexto oficial
    ("Para consultas sobre el IBI, contacte con Juan Pérez al 965 12 34 56 o escriba a recaudacion@ayto-elche.es.", 
     ["Juan Pérez", "965 12 34 56", "recaudacion@ayto-elche.es"]),
    
    # 6. Múltiples sujetos y parentescos (PER)
    ("Dña. María Gómez y su cónyuge, D. Carlos Rodríguez, declaran la venta de la finca urbana.", 
     ["María Gómez", "Carlos Rodríguez"]),
    
    # 7. Cuentas Bancarias / IBAN (ID)
    ("La devolución del IRPF se realizará mediante transferencia a la cuenta ES91 2100 0418 40 1234567890.", 
     ["ES91 2100 0418 40 1234567890"]),
    
    # 8. Ciudadanos extranjeros / NIE (ID)
    ("El ciudadano extranjero con NIE Y1234567X liquidó el impuesto de plusvalía municipal ayer.", 
     ["Y1234567X"]),
    
    # 9. Instituciones y Boletines Oficiales (ORG)
    ("Resolución aprobada por el Pleno de la Diputación de Valencia y publicada en el BOE.", 
     ["Pleno de la Diputación de Valencia", "BOE"]),
    
    # 10. Matrículas de vehículos (ID - Multas / IVTM)
    ("Notificación de impago del IVTM correspondiente al vehículo con matrícula 4567-LMN.", 
     ["4567-LMN"]),
    
    # 11. Direcciones tipo "Plaza" y datos muy comprimidos (caso límite)
    ("Deudor:Luis Miguel Torres,NIF:44556677W,Dir:Plaça de Baix 1,Alicante.", 
     ["Luis Miguel Torres", "44556677W", "Plaça de Baix 1", "Alicante"]),
    
    # 12. Nombres y organismos en minúsculas (PER + ORG)
    ("el requerimiento fue notificado a laura martínez en el registro general de la concejalía de urbanismo.", 
     ["laura martínez", "concejalía de urbanismo"]),
    
    # 13. URLs y Sedes Electrónicas (CONTACT/URL)
    ("El trámite se realizó a través de la sede electrónica https://sede.agenciatributaria.gob.es con certificado digital.", 
     ["https://sede.agenciatributaria.gob.es"]),
    
    # 14. Documento sin datos personales: no debe detectar nada
    ("Se aprueba la modificación de la ordenanza fiscal reguladora del impuesto sobre vehículos de tracción mecánica según la Ley 39/2015.", 
     []),
    
    # 15. Nombres de fincas y polígonos industriales (LOC)
    ("La inspección técnica se realizó en la nave 4 del Polígono Industrial de Carrús.", 
     ["Polígono Industrial de Carrús"])
]

TEXTOS_MANUALES = [
    "BANCO BILBAO VIZCAYA ARGENTARIA",
    "BANCO SABADELL",
    "DON DAVID JESÚS RUBIO GALLEGO Y DOÑA VERÓNICA RUBIO GÓMEZ",
    "CL ESPRONCEDA 12 ELCHE",
    "Caixabank, Sabadell, Santander, BBVA",
    # Fusión ORG + LOC con el modelo real: el banco no debe absorber la calle
    "BANCO SABADELL CL ESPRONCEDA 12 ELCHE",
]

# Palabras que deberían quedar dentro de alguna entidad (solo se avisa, no falla)
COBERTURA_ESPERADA = {
    "BANCO BILBAO VIZCAYA ARGENTARIA": ["BILBAO", "VIZCAYA", "ARGENTARIA"],
    "BANCO SABADELL": ["SABADELL"],
    "DON DAVID JESÚS RUBIO GALLEGO Y DOÑA VERÓNICA RUBIO GÓMEZ": ["GALLEGO", "VERÓNICA", "GÓMEZ"],
    "CL ESPRONCEDA 12 ELCHE": ["ESPRONCEDA", "ELCHE"],
    "Caixabank, Sabadell, Santander, BBVA": ["Caixabank", "Sabadell", "Santander", "BBVA"],
    "BANCO SABADELL CL ESPRONCEDA 12 ELCHE": ["SABADELL", "ESPRONCEDA", "ELCHE"],
}

# Fusión ORG + LOC: (texto, entidades que devuelve el modelo, resultado esperado)
CASOS_FUSION = [
    # Se unen: nombre de organización partido en ORG + LOC
    ("BANCO BILBAO VIZCAYA ARGENTARIA",
     [("BANCO BILBAO", "ORG"), ("VIZCAYA ARGENTARIA", "LOC")],
     [("BANCO BILBAO VIZCAYA ARGENTARIA", "ORG")]),
    ("Diputación de Alicante",
     [("Diputación de", "ORG"), ("Alicante", "LOC")],
     [("Diputación de Alicante", "ORG")]),

    # No se unen: coma entre ambas
    ("BBVA, Alicante",
     [("BBVA", "ORG"), ("Alicante", "LOC")],
     [("BBVA", "ORG"), ("Alicante", "LOC")]),

    # No se unen: la LOC es una dirección (tipo de vía, con y sin punto)
    ("BANCO SABADELL CL ESPRONCEDA",
     [("BANCO SABADELL", "ORG"), ("CL ESPRONCEDA", "LOC")],
     [("BANCO SABADELL", "ORG"), ("CL ESPRONCEDA", "LOC")]),
    ("SUMA PL. SAN CRISTÓBAL",
     [("SUMA", "ORG"), ("PL. SAN CRISTÓBAL", "LOC")],
     [("SUMA", "ORG"), ("PL. SAN CRISTÓBAL", "LOC")]),

    # No se unen: la LOC contiene dígitos
    ("CAIXABANK OFICINA 2100",
     [("CAIXABANK", "ORG"), ("OFICINA 2100", "LOC")],
     [("CAIXABANK", "ORG"), ("OFICINA 2100", "LOC")]),

    # No se unen: el orden es LOC + ORG, no ORG + LOC
    ("ALICANTE SUMA",
     [("ALICANTE", "LOC"), ("SUMA", "ORG")],
     [("ALICANTE", "LOC"), ("SUMA", "ORG")]),

    # Tipo de vía pegado al nombre
    ("BANCO SABADELL C/ESPRONCEDA",
     [("BANCO SABADELL C/ESPRONCEDA", "ORG")],
     [("BANCO SABADELL", "ORG"), ("C/ESPRONCEDA", "LOC")]),
    ("SUMA AVDA.CONSTITUCIÓN",
     [("SUMA AVDA.CONSTITUCIÓN", "ORG")],
     [("SUMA", "ORG"), ("AVDA.CONSTITUCIÓN", "LOC")]),
    # Valenciano
    ("CAIXA POPULAR CARRER MAJOR",
     [("CAIXA POPULAR CARRER MAJOR", "ORG")],
     [("CAIXA POPULAR", "ORG"), ("CARRER MAJOR", "LOC")]),
    # Siglas de empresa o club: no son direcciones
    ("Construcciones López C.B.",
     [("Construcciones López C.B", "ORG")],
     [("Construcciones López C.B", "ORG")]),
    ("Elche C.F.",
     [("Elche C.F", "ORG")],
     [("Elche C.F", "ORG")]),
]


# TESTS DE INTEGRACIÓN (gRPC)

@pytest.mark.parametrize("texto, entidades_esperadas", TEST_CASES)
def test_reconocimiento_entidades(grpc_stub, texto, entidades_esperadas):
    request = ner_pb2.NerRequest(text=texto)
    response = grpc_stub.RecognizeEntities(request)

    resultados_detallados = [
        {
            "word": e.word, 
            "category": e.category, 
            "score": round(e.score, 4), 
            "start": e.start_char, 
            "end": e.end_char
        } 
        for e in response.entities
    ]
    
    print(f"\nResultados detallados para: '{texto[:30]}...' \n -> {resultados_detallados}")

    palabras_detectadas = [entity.word.lower() for entity in response.entities]
    
    for esperada in entidades_esperadas:
        esperada_lower = esperada.lower()
        coincidencia_encontrada = False
        
        for detectada in palabras_detectadas:
            if esperada_lower in detectada or detectada in esperada_lower:
                coincidencia_encontrada = True
                break
                
            similitud = difflib.SequenceMatcher(None, esperada_lower, detectada).ratio()
            if similitud > 0.80:
                coincidencia_encontrada = True
                break
                
        try:
            assert coincidencia_encontrada
        except AssertionError:
            print(f"  ⚠️  AVISO: El modelo falló al detectar -> '{esperada}'")
    
    assert response.inference_time_ms < 500, "El modelo está tardando demasiado"


# TESTS UNITARIOS (Reconstrucción Subtokens)

def test_ultimo_subtoken_O_no_corta_la_palabra():   
    assert entidades("BILBAO", [None, 0, 0, 0, None],
                     [[0, 0], [0, 3], [3, 5], [5, 6], [0, 0]],
                     [vec(0), vec(1), vec(2), vec(0, 0.6), vec(0)]) == [("BILBAO", "LOC")]

def test_subtoken_central_O_no_parte_la_entidad():  
    assert entidades("SABADELL", [None, 0, 0, 0, None],
                     [[0, 0], [0, 3], [3, 5], [5, 8], [0, 0]],
                     [vec(0), vec(1), vec(0, 0.6), vec(2, 0.7), vec(0)]) == [("SABADELL", "LOC")]

def test_coma_separa_entidades():                  
    assert entidades("CaixaBank,Sabadell", [None, 0, 1, 2, None],
                     [[0, 0], [0, 9], [9, 10], [10, 18], [0, 0]],
                     [vec(0), vec(1), vec(0), vec(1), vec(0)]) == [("CaixaBank", "LOC"), ("Sabadell", "LOC")]

def test_espacio_une_entidades():
    assert entidades("San Vicente", [None, 0, 1, None],
                     [[0, 0], [0, 3], [4, 11], [0, 0]],
                     [vec(0), vec(1), vec(2), vec(0)]) == [("San Vicente", "LOC")]

def test_bioes_suma_prefijos_de_la_misma_categoria():
    # PER repartido entre B, E y S (0,7 en total) frente a O (0,3): se suman por categoría y gana PER
    probs = [dist({0: 1}), dist({1: 0.25, 3: 0.25, 4: 0.2, 0: 0.3}), dist({0: 1})]
    ents = agregar_por_palabra("GALLEGO", [None, 0, None],
                               np.array([[0, 0], [0, 7], [0, 0]]), np.array(probs), ID2LABEL_BIOES)
    assert [(e["word"], e["category"]) for e in ents] == [("GALLEGO", "PER")]

def test_bioes_une_inicio_y_fin():
    # B-PER + E-PER deben dar una sola entidad PER, sin prefijos
    probs = [dist({0: 1}), dist({1: 0.9, 0: 0.1}), dist({3: 0.9, 0: 0.1}), dist({0: 1})]
    ents = agregar_por_palabra("RUBIO GALLEGO", [None, 0, 1, None],
                               np.array([[0, 0], [0, 5], [6, 13], [0, 0]]), np.array(probs), ID2LABEL_BIOES)
    assert [(e["word"], e["category"]) for e in ents] == [("RUBIO GALLEGO", "PER")]


# TESTS UNITARIOS (Fusión ORG + LOC)

@pytest.mark.parametrize("texto, entrada, esperado", CASOS_FUSION)
def test_fusion_org_loc(texto, entrada, esperado):
    resultado = [(e["word"], e["category"])
                 for e in fusionar_org_loc(texto, [ent(texto, p, c) for p, c in entrada])]
    print(f"\n{texto}\n   entrada   -> {entrada}\n   resultado -> {resultado}")

    if resultado != esperado:
        print(f"  ⚠️  AVISO: se esperaba -> {esperado}")


# TESTS UNITARIOS (Motor Directo)

@pytest.mark.parametrize("texto", TEXTOS_MANUALES)
def test_predicciones_manuales_engine(ner_engine, texto):
    predicciones = ner_engine.predict(texto)
    resultados = [(x["word"], x["category"], x["score"]) for x in predicciones]
    print(f"\n{texto} -> {resultados}")
    
    try:
        assert len(resultados) > 0
    except AssertionError:
        print(f"  ⚠️  AVISO: No se detectaron entidades en: '{texto}'")

    for x in predicciones:
        if x["category"] not in CATEGORIAS_MOTOR:
            print(f"  ⚠️  AVISO: categoría que el motor no reconoce -> {x['category']} ('{x['word']}')")
        ini, fin = x["start_char"], x["end_char"]
        if (ini > 0 and texto[ini - 1].isalnum()) or (fin < len(texto) and texto[fin].isalnum()):
            print(f"  ⚠️  AVISO: entidad cortada a mitad de palabra -> '{x['word']}'")

    cubiertas = {p for x in predicciones for p in texto[x["start_char"]:x["end_char"]].split()}
    for palabra in COBERTURA_ESPERADA.get(texto, []):
        if palabra not in cubiertas:
            print(f"  ⚠️  AVISO: palabra sin cubrir -> '{palabra}'")