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

import time
import grpc
from src.protos import ner_pb2, ner_pb2_grpc
from src.model.engine import NerEngine
from src.config.logger import setup_logger

logger = setup_logger("ner-service-handler")

class NerServiceServicer(ner_pb2_grpc.NerServiceServicer):
    def __init__(self):
        logger.info("Configurando NerServiceServicer...")
        # Carga al arrancar: el servidor no acepta peticiones hasta tener el modelo listo
        self.engine = NerEngine()

    def RecognizeEntities(self, request, context):
        #if not request.text:
        #    context.abort(grpc.StatusCode.INVALID_ARGUMENT, "El texto de entrada no ha sido informado.")
        #if not request.text.strip():
        #    context.abort(grpc.StatusCode.INVALID_ARGUMENT, "El texto de entrada no puede estar vacío.")
        # Sin texto no hay entidades: respuesta vacía, no error (páginas en blanco o solo imagen)
        if not request.text.strip():
            logger.warning("El texto de entrada que se ha recibido esta vacio / todo a blancos. Generando respuesta vacia de NER.")
            return ner_pb2.NerResponse()

        start_time = time.perf_counter()
        
        try:
            predictions = self.engine.predict(request.text)

            entities_pb = [
                ner_pb2.Entity(
                    word=pred["word"],
                    category=pred["category"],
                    score=pred["score"],
                    start_char=pred["start_char"],
                    end_char=pred["end_char"]
                ) for pred in predictions
            ]
            
            inference_time_ms = int((time.perf_counter() - start_time) * 1000)
            
            logger.info(f"Procesados {len(request.text)} caracteres en {inference_time_ms}ms. Entidades: {len(entities_pb)}")
            
            return ner_pb2.NerResponse(
                entities=entities_pb,
                inference_time_ms=inference_time_ms
            )
            
        except Exception as e:
            logger.error(f"Error procesando petición: {str(e)}", exc_info=True)
            context.abort(grpc.StatusCode.INTERNAL, "Fallo interno en el procesado del modelo.")