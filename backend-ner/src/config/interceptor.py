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
from opentelemetry import trace
from opentelemetry.propagate import extract

class OpenTelemetryServerInterceptor(grpc.ServerInterceptor):
    def intercept_service(self, continuation, handler_call_details):

       handler = continuation(handler_call_details)
       if handler is None or handler.unary_unary is None:
           return handler
       ctx = extract(dict(handler_call_details.invocation_metadata or []))
       tracer = trace.get_tracer(__name__)

       def con_span(request, context):
           with tracer.start_as_current_span(handler_call_details.method, context=ctx,
                                             kind=trace.SpanKind.SERVER):
               return handler.unary_unary(request, context)

       return grpc.unary_unary_rpc_method_handler(
           con_span,
           request_deserializer=handler.request_deserializer,
           response_serializer=handler.response_serializer)
    