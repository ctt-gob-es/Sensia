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

from typing import Optional

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class AppSettings(BaseSettings):
    """
    Configuración del servicio NER.

    Prioridad: variables de entorno > fichero .env > valores por defecto.
    Los valores por defecto son seguros: sin configuración, el servicio
    arranca en modo producción y exige mTLS.
    """

    # "development" desactiva el mTLS aunque ENFORCE_SECURITY sea true
    ENV: str = "production"
    LOG_LEVEL: str = "INFO"

    # Red gRPC
    GRPC_BIND_TYPE: str = "TCP"
    GRPC_HOST: str = "0.0.0.0"
    GRPC_PORT: int = 50051
    GRPC_SOCKET_PATH: str = "/tmp/roberta-ner.sock"

    # Modelo (rutas relativas al directorio de trabajo)
    # Las escribe scripts/export_onnx.py: (scripts/.env.build)
    MODEL_PATH: str = "models/roberta_bsc.onnx"
    TOKENIZER_PATH: str = "models/tokenizer"
    ONNX_EXECUTION_PROVIDER: str = "CPUExecutionProvider"
    NER_MERGE_ORG_LOC: bool = True
    NER_AGGREGATION: str = "first"

    # mTLS en el puerto gRPC
    ENFORCE_SECURITY: bool = True
    TLS_CERT_FILE: Optional[str] = None
    TLS_KEY_FILE: Optional[str] = None
    TLS_CA_FILE: Optional[str] = None

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @field_validator("NER_AGGREGATION")
    @classmethod
    def validate_aggregation(cls, v: str) -> str:
        v = v.strip().lower()
        if v not in ("first", "average"):
            raise ValueError("NER_AGGREGATION debe ser 'first' o 'average'")
        return v

settings = AppSettings()