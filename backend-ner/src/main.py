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
import time
import signal
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from src.protos import ner_pb2, ner_pb2_grpc
from src.services.ner_service import NerServiceServicer
from src.config.settings import settings
from src.config.logger import setup_logger
from src.config.interceptor import OpenTelemetryServerInterceptor

logger = setup_logger("ner-microservice")


class RequestLoggerInterceptor(grpc.ServerInterceptor):
    def intercept_service(self, continuation, handler_call_details):
        logger.info(f"[INTERCEPTOR] Petición entrante detectada. Ruta solicitada: {handler_call_details.method}")
        
        return continuation(handler_call_details)
    

class GracefulKiller:
    def __init__(self, server, socket_path=None):
        self.kill_now = False
        self.server = server
        self.socket_path = socket_path
        signal.signal(signal.SIGINT, self.exit_gracefully)
        signal.signal(signal.SIGTERM, self.exit_gracefully)

    def exit_gracefully(self, *args):
        logger.info("Recibida señal de apagado. Drenando conexiones...")
        stop_event = self.server.stop(grace=5)
        stop_event.wait()
        
        if self.socket_path and os.path.exists(self.socket_path):
            try:
                os.remove(self.socket_path)
                logger.info(f"Socket UNIX eliminado limpiamente: {self.socket_path}")
            except OSError as e:
                logger.error(f"No se pudo eliminar el socket: {e}")
                
        logger.info("Servidor apagado correctamente. Goodbye.")
        sys.exit(0)


def serve():
    
    workers = (os.cpu_count() or 1) * 2
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=workers),
        interceptors=(OpenTelemetryServerInterceptor(), RequestLoggerInterceptor()),
        options=[
            ('grpc.max_send_message_length', 50 * 1024 * 1024),
            ('grpc.max_receive_message_length', 50 * 1024 * 1024),
            # 0: si otro proceso ocupa el puerto --> falla
            ('grpc.so_reuseport', 0),
        ]
    )

    health_servicer = health.HealthServicer(experimental_non_blocking=True)
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)

    socket_to_clean = None

    if settings.GRPC_BIND_TYPE == "UDS":
        bind_address = f"unix://{settings.GRPC_SOCKET_PATH}"
        socket_to_clean = settings.GRPC_SOCKET_PATH
        
        os.makedirs(os.path.dirname(socket_to_clean), exist_ok=True)
        
        if os.path.exists(socket_to_clean):
            logger.warning(f"Socket antiguo detectado en {socket_to_clean}. Removiendo...")
            os.remove(socket_to_clean)

        logger.info(f"Escuchando por socket UNIX en: {bind_address}")
        server.add_insecure_port(bind_address)
        
    else:
        bind_address = f"{settings.GRPC_HOST}:{settings.GRPC_PORT}"

        if settings.ENFORCE_SECURITY and settings.ENV != "development":
            if settings.TLS_CERT_FILE and settings.TLS_KEY_FILE and settings.TLS_CA_FILE:
                try:
                    with open(settings.TLS_KEY_FILE, 'rb') as f:
                        private_key = f.read()
                    with open(settings.TLS_CERT_FILE, 'rb') as f:
                        certificate_chain = f.read()
                    with open(settings.TLS_CA_FILE, 'rb') as f:
                        root_ca = f.read()

                    server_credentials = grpc.ssl_server_credentials(
                        [(private_key, certificate_chain)],
                        root_certificates=root_ca,
                        require_client_auth=True
                    )
                    logger.info("TCP con mTLS")
                    server.add_secure_port(bind_address, server_credentials)
                except FileNotFoundError as e:
                    logger.critical(f"No se encontraron los certificados mTLS: {e}")
                    sys.exit(1)
            else:
                logger.critical("ENFORCE_SECURITY es True pero faltan las rutas de los certificados TLS en el .env. Abortando.")
                sys.exit(1)
                
        else:
            logger.warning("Red TCP INSEGURA (Sin cifrar)")
            server.add_insecure_port(bind_address)

    ner_service_instance = NerServiceServicer() 
    ner_pb2_grpc.add_NerServiceServicer_to_server(ner_service_instance, server)
    
    server.start()
    
    if settings.GRPC_BIND_TYPE == "UDS":
        try:
            os.chmod(socket_to_clean, 0o666)
            logger.info("Permisos del socket establecidos a 0666")
        except Exception as e:
            logger.error(f"Error cambiando permisos: {e}")

    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)
    logger.info(f"Servidor listo para operar via {settings.GRPC_BIND_TYPE} en: {bind_address}")

    GracefulKiller(server, socket_to_clean)
    
    server.wait_for_termination()



if __name__ == '__main__':
    serve()
    
