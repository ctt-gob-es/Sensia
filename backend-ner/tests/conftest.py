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

import grpc
import pytest
from src.protos import ner_pb2, ner_pb2_grpc

@pytest.fixture(scope="session")
def grpc_stub():
    channel = grpc.insecure_channel('localhost:50051')
    try:
        grpc.channel_ready_future(channel).result(timeout=3)
    except grpc.FutureTimeoutError:
        channel.close()
        pytest.skip("Servicio NER no levantado en localhost:50051 (make run)")
    stub = ner_pb2_grpc.NerServiceStub(channel)
    yield stub
    channel.close()