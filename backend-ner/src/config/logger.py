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
import logging
import sys
import time
from pythonjsonlogger import jsonlogger
from src.config.settings import settings
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider

# Inicializar el SDK OpenTelemetry
_provider = TracerProvider()
if os.getenv("OTEL_TRACES_EXPORTER", "none") == "otlp":
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
    _provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
trace.set_tracer_provider(_provider)

# Filtro nativo OpenTelemetry-->gRPC
class OpenTelemetryFilter(logging.Filter):
    def filter(self, record):
        span = trace.get_current_span()
        if span.is_recording():
            record.trace_id = format(span.get_span_context().trace_id, "032x")
        else:
            record.trace_id = ""
        return True


def setup_logger(name: str) -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(settings.LOG_LEVEL.upper())
    if logger.handlers: return logger

    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(OpenTelemetryFilter())
    
    formatter = jsonlogger.JsonFormatter(
        '%(asctime)s %(levelname)s %(name)s %(trace_id)s %(message)s',
        rename_fields={"levelname": "severity", "asctime": "timestamp"},
        datefmt='%Y-%m-%dT%H:%M:%SZ'
    )
    formatter.converter = time.gmtime
    handler.setFormatter(formatter)
    logger.addHandler(handler)
    return logger
