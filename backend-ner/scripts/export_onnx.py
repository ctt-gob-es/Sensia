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
import sys
import json
from datetime import datetime, timezone
from pathlib import Path
import logging
import numpy as np
import torch
import onnxruntime as ort
from huggingface_hub import HfApi
from transformers import AutoModelForTokenClassification, AutoTokenizer

logging.basicConfig(level=logging.INFO, format='%(levelname)s: %(message)s')
logger = logging.getLogger("onnx-exporter")

# Configuración: variables de entorno (scripts/.env.build)
MODEL_NAME = os.environ.get("MODEL_NAME", "BSC-LT/roberta_model_for_anonimization")
MODEL_REVISION = os.environ.get("MODEL_REVISION", "").strip() or None
MODEL_PATH = Path(os.environ.get("MODEL_PATH", "models/roberta_bsc.onnx"))
TOKENIZER_PATH = Path(os.environ.get("TOKENIZER_PATH", "models/tokenizer"))

def export_to_onnx():
    # La exportación --> necesita Internet / el servicio en ejecución --> no
    for var in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE"):
        if os.environ.get(var, "").strip().lower() in ("1", "true", "yes"):
            logger.critical(f"{var} está activado y la exportación necesita descargar el modelo. "
                            f"Ejecútala con {var}=0.")
            sys.exit(1)


    modelo = MODEL_NAME
    revision = MODEL_REVISION
    if revision is None:
        logger.warning("MODEL_REVISION sin fijar: se exporta la última versión del modelo")

    commit = HfApi().model_info(modelo, revision=revision).sha
    logger.info(f"Descargando {modelo} (revisión solicitada: {revision or 'última'}, commit: {commit})")

    # Mismo commit para tokenizador, configuración y pesos
    tokenizer = AutoTokenizer.from_pretrained(modelo, revision=commit, add_prefix_space=True)
    model = AutoModelForTokenClassification.from_pretrained(modelo, revision=commit, return_dict=False)
    model.eval()

    # Tokenizador y configuración que usará el servicio (TOKENIZER_PATH)
    tokenizer_dir = TOKENIZER_PATH
    tokenizer_dir.mkdir(parents=True, exist_ok=True)
    tokenizer.save_pretrained(tokenizer_dir)
    model.config.save_pretrained(tokenizer_dir)
    logger.info(f"Tokenizador y configuración guardados en: {tokenizer_dir}")

    output_path = MODEL_PATH
    output_path.parent.mkdir(parents=True, exist_ok=True)

    dummy_text = "El Barcelona Supercomputing Center está en Cataluña."
    inputs = tokenizer(dummy_text, return_tensors="pt", padding=True, truncation=True)

    input_names = ["input_ids", "attention_mask"]
    output_names = ["logits"]

    # Ejes dinámicos: tamaño de lote y longitud de secuencia variables
    dynamic_axes = {
        "input_ids": {0: "batch_size", 1: "sequence_length"},
        "attention_mask": {0: "batch_size", 1: "sequence_length"},
        "logits": {0: "batch_size", 1: "sequence_length"}
    }

    logger.info(f"Compilando y exportando grafo computacional a ONNX en: {output_path}...")

    with torch.no_grad():
        torch.onnx.export(
            model,
            args=(inputs["input_ids"], inputs["attention_mask"]),
            f=str(output_path),
            input_names=input_names,
            output_names=output_names,
            dynamic_axes=dynamic_axes,
            opset_version=18,
            do_constant_folding=True
        )

    logger.info("Exportación completada. Verificando el modelo ONNX...")

    # Verificación: ONNX Runtime lo carga y sus salidas coinciden con las de PyTorch
    try:
        session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
        ort_logits = session.run(None, {
            "input_ids": inputs["input_ids"].numpy(),
            "attention_mask": inputs["attention_mask"].numpy()
        })[0]
        with torch.no_grad():
            pt_logits = model(inputs["input_ids"], inputs["attention_mask"])[0].numpy()

        diferencia = float(np.max(np.abs(ort_logits - pt_logits)))
        logger.info(f"Forma de los logits: {ort_logits.shape}. Diferencia máxima con PyTorch: {diferencia:.2e}")
        if diferencia > 1e-3:
            raise ValueError(f"las salidas de ONNX y PyTorch difieren ({diferencia:.2e})")

    except Exception as e:
        logger.critical(f"Fallo en la verificación del modelo ONNX: {str(e)}")
        sys.exit(1)

    # Trazabilidad: qué modelo exacto se ha exportado
    origen = {
        "modelo": modelo,
        "revision_solicitada": revision,
        "commit": commit,
        "exportado": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    (output_path.parent / "origen_modelo.json").write_text(
        json.dumps(origen, indent=2, ensure_ascii=False), encoding="utf-8")
    logger.info(f"Modelo listo. Origen registrado en {output_path.parent / 'origen_modelo.json'}")


if __name__ == "__main__":
    export_to_onnx()
