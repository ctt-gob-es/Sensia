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



const jwt = require('jsonwebtoken');
const { User } = require('../models/user');
const { SystemConfig } = require('../models/systemConfig');
const SECRET_KEY = process.env.JWT_SECRET;





const maintenanceMiddleware = async (req, res, next) => {

  if (req.path === '/api/auth/status' || req.originalUrl === '/api/auth/status') {
    return next();
  }

  if (req.path === '/api/auth/altcha-challenge' || req.originalUrl === '/api/auth/altcha-challenge') {
    return next();
  }

  if (req.path === '/api/auth/login' || req.originalUrl === '/api/auth/login') {
    return next();
  }

  if (process.env.HARD_MAINTENANCE === 'true') {
    return res.status(503).json({ error: 'Sistema fuera de servicio por mantenimiento técnico. Vuelve más tarde.' });
  } 

  let config;
  try {
    config = await SystemConfig.findByPk(1);
  } catch (error) {
    return res.status(500).json({ error: 'No se pudo comprobar el estado del sistema.' });
  }
  if (!config) {
    return res.status(500).json({ error: 'Configuración global no inicializada en la BD.' });
  }

  if (!config.maintenanceMode) return next();

  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1];

  if (!token) {
    return res.status(503).json({ error: 'La web está pausada temporalmente. Vuelve pronto.' });
  }

  try {
    const decoded = jwt.verify(token, SECRET_KEY);
    const user = await User.findByPk(decoded.id);

    if (user && user.role === 'admin' && !user.isBlocked) {
      return next();
    }

    return res.status(503).json({ error: 'La web está pausada temporalmente. Solo acceso para administradores.' });
  } catch (error) {
    return res.status(503).json({ error: 'La web está pausada temporalmente.' });
  }
};



module.exports = { maintenanceMiddleware };