/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE  en la raíz del proyecto para ver los
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

const fs = require('fs');
const path = require('path');
const { createLogger, format, transports } = require('winston');

const produccion = process.env.NODE_ENV === 'production';
const nivel = (process.env.LOG_LEVEL || 'info').toLowerCase();
const dirLogs = process.env.DIR_LOGS; // opcional: sin él solo se escribe en consola

const formatoTexto = format.printf(({ timestamp, level, message, reqId, stack, ...meta }) => {
  const idStr = reqId ? `[ReqID: ${reqId}] ` : '';
  const metaStr = Object.keys(meta).length ? JSON.stringify(meta) : '';
  return `${timestamp} [${level.toUpperCase()}] ${idStr}: ${message} ${stack || metaStr}`;
});

const salidas = [
  // En producción sin colores por claridad en Loki
  new transports.Console(produccion ? {} : { format: format.colorize({ all: true }) }),
];

if (dirLogs) {
  fs.mkdirSync(dirLogs, { recursive: true });
  const rotacion = { maxsize: 10 * 1024 * 1024, maxFiles: 10, tailable: true };
  salidas.push(
    new transports.File({ filename: path.join(dirLogs, 'error.log'), level: 'error', ...rotacion }),
    new transports.File({ filename: path.join(dirLogs, 'combined.log'), ...rotacion }),
  );
}

const logger = createLogger({
  level: nivel,
  format: format.combine(
    format.timestamp({ format: 'YYYY-MM-DD HH:mm:ss' }),
    format.errors({ stack: true }),
    format.splat(),
    formatoTexto,
  ),
  defaultMeta: { service: process.env.OTEL_SERVICE_NAME || 'backend-app' },
  transports: salidas,
});

module.exports = logger;
