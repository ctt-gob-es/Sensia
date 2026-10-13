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


const express = require('express');
const cors = require('cors');
const path = require('path');

const authRoutes = require('./routes/auth.routes');
const userRoutes = require('./routes/user.routes');
const templateRoutes = require('./routes/template.routes');
const soapProxy = require('./utils/soap-proxy');
const {maintenanceMiddleware} = require('./middlewares/maintenance.middleware');
const { gestionarError } = require('./utils/error-helper');
const { verifyToken } = require('./middlewares/auth.middleware');

const app = express();
app.set('trust proxy', 1);

// Límite del cuerpo. Los PDF viajan en base64 (ocupan 4/3): 50 MB de fichero
// necesitan unos 70 MB. Debe quedar por debajo del client_max_body_size de nginx.
const maxBodySize = process.env.MAX_BODY_SIZE || '70mb';

// CORS solo si se configura (desarrollo: CORS_ORIGIN=http://localhost:4200).
// En producción no hace falta: frontend y API comparten origen a través de nginx.
const corsOrigin = process.env.CORS_ORIGIN;
app.use(cors({ origin: corsOrigin ? corsOrigin.split(',') : false }));

app.use(express.json({ limit: maxBodySize }));
app.use(express.urlencoded({ limit: maxBodySize, extended: true }));

// Salud para Docker: antes de mantenimiento y autenticación
app.get('/health', (req, res) => res.status(200).json({ status: 'ok' }));
app.use(maintenanceMiddleware);
app.use('/api/auth', authRoutes);
app.use('/api', verifyToken);
app.use('/api/users', userRoutes);
app.use('/api/templates', templateRoutes);
app.use('/api/sensiaws', soapProxy);



app.use((err, req, res, next) => {
  if (err.type === 'entity.too.large') {
    return res.status(413).json({ error: 'El documento supera el tamaño máximo permitido.' });
  }
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'Petición mal formada.' });
  }
  gestionarError(res, err, 'Error interno del servidor.', 500);
});


module.exports = app;



