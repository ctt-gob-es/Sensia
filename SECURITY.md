# Política de seguridad

## Versiones con soporte

| Versión | Soporte de seguridad |
| :--- | :--- |
| 1.0.x | Sí |
| Anteriores | No |

Las correcciones de seguridad se publican sobre la última versión disponible. Se recomienda mantener las instalaciones actualizadas.

## Cómo informar de una vulnerabilidad

**No abras un *issue* público** para informar de una vulnerabilidad de seguridad.

Envía el informe de forma privada por una de estas vías:
- El canal indicado en la página oficial de SensIA en el Centro de Transferencia de Tecnología (CTT): https://administracionelectronica.gob.es/ctt/sensia
- El canal privado de GitHub que dispone el CTT.

Incluye, en la medida de lo posible:

- Componente afectado (backend-ws, backend-app, frontend-app, backend-ner o scripts de despliegue) y versión.
- Descripción de la vulnerabilidad y de su posible impacto.
- Pasos para reproducirla o prueba de concepto.
- Configuración relevante, sin incluir secretos, tokens ni contraseñas.

**No incluyas datos personales reales.** Si la reproducción requiere un documento, utiliza documentos sintéticos o datos ficticios.

## Qué se considera una vulnerabilidad

Además de las vulnerabilidades habituales (autenticación, control de acceso, inyección, exposición de secretos, denegación de servicio, etc.), dado el propósito de SensIA se consideran incidencias de seguridad:

- Que el contenido censurado pueda recuperarse del documento final (texto subyacente, capas ocultas, metadatos, anotaciones o imágenes no rasterizadas).
- Que un usuario pueda acceder a documentos, resultados o históricos de otro usuario.
- Que sea posible invocar el servicio SOAP eludiendo el token de autenticación o la validación mTLS.
- Que los documentos o archivos temporales persistan más allá del periodo de retención configurado.

Las omisiones de detección (un dato que el motor no identifica) se consideran incidencias funcionales y pueden comunicarse mediante *issues* públicos, siempre con ejemplos sintéticos. Consulta las limitaciones conocidas en el `README.md`.

## Proceso de gestión

1. Acuse de recibo.
2. Análisis y evaluación del impacto.
3. Desarrollo y verificación de la corrección.
4. Publicación de una versión corregida y, cuando proceda, del aviso de seguridad correspondiente, con reconocimiento a quien haya comunicado la vulnerabilidad si así lo desea.

Se ruega no divulgar públicamente la vulnerabilidad hasta que se haya publicado la corrección o se haya acordado una fecha de divulgación coordinada.

## Alcance

Esta política cubre el código propio de SensIA incluido en este repositorio.

- Las vulnerabilidades en componentes de terceros (relacionados en `NOTICE.md`) deben comunicarse también a sus respectivos proyectos.
- Las incidencias en una instalación concreta de SensIA gestionada por otra entidad deben comunicarse a los responsables de esa instalación.

## Recomendaciones para quienes despliegan SensIA

- Mantener el fichero `.env` fuera de cualquier repositorio y con permisos restringidos, y rotar `SOAP_API_TOKEN` y las credenciales de administración ante cualquier sospecha de exposición.
- Activar mTLS en las integraciones M2M y mantener el captcha activo en entornos expuestos a Internet.
- Exponer únicamente el proxy de entrada, sin publicar directamente los puertos internos del motor.
- Aplicar las actualizaciones de SensIA y reconstruir las imágenes periódicamente para incorporar los parches de seguridad de las dependencias.

Cada entidad que despliega SensIA actúa como responsable del tratamiento de los documentos que procesa. Si una vulnerabilidad diera lugar a una exposición de datos personales, corresponde a dicha entidad evaluar la notificación de la brecha a la autoridad de control conforme al artículo 33 del RGPD, así como gestionarla en el marco del Esquema Nacional de Seguridad cuando le resulte de aplicación.
