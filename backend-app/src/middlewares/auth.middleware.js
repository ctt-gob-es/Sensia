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
const SECRET_KEY = process.env.JWT_SECRET;




const verifyToken = (req, res, next) => {
  if (req.userId) return next(); // ya verificado por app.use('/api', verifyToken)
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1]; 

  if (!token) {
    return res.status(403).json({ auth: false, message: 'Se requiere token de acceso.' });
  }

  jwt.verify(token, SECRET_KEY, async (err, decoded) => {
    if (err) {
      return res.status(401).json({ auth: false, message: 'Token inválido o expirado.' });
    }
    try {
      // Bloquear o eliminar y cortar su sesión
      const user = await User.findByPk(decoded.id, { attributes: ['id', 'isBlocked'] });
      if (!user || user.isBlocked) {
        return res.status(401).json({ auth: false, message: 'La sesión ya no es válida.' });
      }
      req.userId = user.id;
      next();
    } catch (error) {
      res.status(500).json({ auth: false, message: 'Error al verificar la sesión.' });
    }    
  });
};





const verifyAdmin = async (req, res, next) => {
  try {
    const user = await User.findByPk(req.userId);
    
    if (!user || user.role !== 'admin') {
      return res.status(403).json({ error: 'Acceso denegado. Se requieren permisos de administrador.' });
    }
    
    next();
  } catch (error) {
    res.status(500).json({ error: 'Error al verificar los permisos del usuario.' });
  }
};


module.exports = { verifyToken, verifyAdmin };

