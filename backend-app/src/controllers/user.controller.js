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


const { User } = require('../models/user');
const crypto = require('crypto');
const { sendEmail } = require('../utils/mailer');
const defaults = require('../config/defaults.json');
const { gestionarError } = require('../utils/error-helper');
const { SystemConfig } = require('../models/systemConfig');
const bcrypt = require('bcryptjs');
const ROLES = ['user', 'admin'];



function generarPassword(longitud = 16, {
  minusculas = true,
  mayusculas = true,
  numeros = true,
  simbolos = true,
} = {}) {
  const grupos = [];
  if (minusculas) grupos.push('abcdefghijklmnopqrstuvwxyz');
  if (mayusculas) grupos.push('ABCDEFGHIJKLMNOPQRSTUVWXYZ');
  if (numeros)    grupos.push('0123456789');
  if (simbolos)   grupos.push('!@#$%^&*()-_=+[]{};:,.?');

  if (grupos.length === 0) throw new Error('Selecciona al menos un tipo de carácter');
  if (longitud < grupos.length) throw new Error(`La longitud mínima es ${grupos.length}`);

  const alfabeto = grupos.join('');

  // Garantiza al menos un carácter de cada grupo
  const chars = grupos.map(g => g[crypto.randomInt(g.length)]);

  while (chars.length < longitud) {
    chars.push(alfabeto[crypto.randomInt(alfabeto.length)]);
  }

  for (let i = chars.length - 1; i > 0; i--) {
    const j = crypto.randomInt(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }

  return chars.join('');
}


exports.getProfile = async (req, res) => {
  try {
    const user = await User.findByPk(req.userId, {
      attributes: ['id', 'username', 'email', 'settings', 'createdAt']
    });

    if (!user) return res.status(404).json({ error: 'Usuario no encontrado.' });

    res.status(200).json(user);
  } catch (error) {
    gestionarError(res, error, 'Error al obtener el perfil.', 500);
  }
};



// Preferencias con las que se crea un usuario nuevo
exports.getDefaultSettings = (req, res) => {
  res.status(200).json(defaults.userSettings);
};


exports.putProfile = async (req, res) => {
  try {
    const userId = req.userId;
    const { settings } = req.body;

    const user = await User.findByPk(userId);
    
    if (!user) return res.status(404).json({ error: 'Usuario no encontrado.' });

    if (settings) user.settings = settings;

    await user.save();

    res.status(200).json({
      message: 'Perfil actualizado con éxito.',
      user: { username: user.username, email: user.email }
    });
  } catch (error) {
    gestionarError(res, error, 'Error al actualizar el perfil.', 500);
  }
};




exports.getUsers = async (req, res) => {
  try {
    const users = await User.findAll({
      attributes: ['id', 'username', 'email', 'role', 'isBlocked', 'createdAt']
    });

    res.status(200).json(users);
  } catch (error) {
    gestionarError(res, error, 'Error al obtener la lista de usuarios.', 500);
  }
};




exports.postUsers = async (req, res) => {
  try {

    let { username, email, password, role } = req.body;

    if (!username || !email) {
      return res.status(400).json({ error: 'Usuario y email son obligatorios.' });
    }

    if (role && !ROLES.includes(role)) {
      return res.status(400).json({ error: 'Rol no válido.' });
    }

    if (!password) {
      password = generarPassword(12);
    }

    if (typeof password !== 'string' || password.length < 12) {
      return res.status(400).json({ error: 'La nueva contraseña debe tener al menos 12 caracteres.' });
    }

    const newUser = await User.create({
      username,
      email,
      password: password,
      role: role || 'user',
      settings: defaults.userSettings
    });

    const asunto = 'Bienvenido a la plataforma - Tus credenciales';
    const mensajeHtml = `
      <h2>¡Hola, ${username}!</h2>
      <p>Un administrador ha creado una cuenta para ti.</p>
      <p>Tus datos de acceso son:</p>
      <ul>
        <li><strong>Usuario:</strong> ${username}</li>
        <li><strong>Contraseña temporal:</strong> ${password}</li> </ul>
      <p>Te recomendamos cambiar esta contraseña al entrar por primera vez.</p>
    `;

    let correoEnviado = true;
    try {
      await sendEmail(email, asunto, mensajeHtml);
    } catch (error) {
      correoEnviado = false;
      console.error(`Error al enviar correo a ${email}:`, error);
    }

    res.status(201).json({
      message: correoEnviado
        ? 'Usuario creado y correo enviado correctamente.'
        : 'Usuario creado, pero no se ha podido enviar el correo. Comunícale la contraseña por otra vía.',
      user: { id: newUser.id, username: newUser.username, email: newUser.email }
    });

  } catch (error) {
    if (error.name === 'SequelizeUniqueConstraintError') {
      return res.status(409).json({ error: 'El nombre de usuario o email ya están en uso.' });
    }
    gestionarError(res, error, 'Error interno al crear el usuario.', 500);
  }
};






exports.putUsers = async (req, res) => {
  try {
    const { username, email, role, password } = req.body;
    const userId = req.params.id;

    const user = await User.findByPk(userId);
    if (!user) {
      return res.status(404).json({ error: 'Usuario no encontrado.' });
    }

    if (username) user.username = username;
    if (email) user.email = email;

    if (password && (typeof password !== 'string' || password.length < 12)) {
      return res.status(400).json({ error: 'La nueva contraseña debe tener al menos 12 caracteres.' });
    }    

    // Vacío = conservar la contraseña actual (el hook la cifra al guardar)
    if (password) user.password = password;
    

    if (role && !ROLES.includes(role)) {
      return res.status(400).json({ error: 'Rol no válido.' });
    }
    if (role) {
      if (userId == req.userId && role !== 'admin') {
        return res.status(400).json({ error: 'No puedes quitarte el rol de administrador a ti mismo.' });
      }
      user.role = role;
    }

    await user.save();

    res.status(200).json({
      message: 'Usuario actualizado correctamente.',
      user: { id: user.id, username: user.username, email: user.email, role: user.role }
    });

  } catch (error) {
    if (error.name === 'SequelizeUniqueConstraintError') {
      return res.status(409).json({ error: 'El nombre de usuario o email ya están en uso por otra cuenta.' });
    }
    gestionarError(res, error, 'Error interno al modificar el usuario.', 500);
  }
};





exports.deleteUsers = async (req, res) => {
  try {
    const userId = req.params.id;

    if (userId == req.userId) {
      return res.status(400).json({ error: 'Operación denegada. No puedes eliminar tu propia cuenta.' });
    }

    const deletedRows = await User.destroy({ where: { id: userId } });

    if (deletedRows === 0) {
      return res.status(404).json({ error: 'Usuario no encontrado.' });
    }

    res.status(200).json({ message: 'Usuario eliminado de forma permanente.' });

  } catch (error) {
    gestionarError(res, error, 'Error interno al eliminar el usuario.', 500);

  }
};





exports.blockUsers = async (req, res) => {
  try {
    const userIdToToggle = req.params.id;

    if (userIdToToggle == req.userId) {
      return res.status(400).json({ error: 'No puedes bloquear tu propia cuenta.' });
    }

    const user = await User.findByPk(userIdToToggle);
    if (!user) {
      return res.status(404).json({ error: 'Usuario no encontrado.' });
    }

    user.isBlocked = !user.isBlocked;

    await user.save();

    const estado = user.isBlocked ? 'bloqueado' : 'desbloqueado';
    res.status(200).json({
      message: `Usuario ${estado} con éxito.`,
      isBlocked: user.isBlocked
    });

  } catch (error) {
    gestionarError(res, error, 'Error interno al cambiar el estado el usuario.', 500);

  }
};







exports.putSystemConfig = async (req, res) => {
  try {
    if (process.env.HARD_MAINTENANCE === 'true') {
      return res.status(409).json({ error: 'El servidor está en mantenimiento técnico (hard). No puedes cambiar configuraciones ahora.' });
    }

    const { maintenanceMode, captchaEnabled, registrationEnabled, passwordRecoveryEnabled } = req.body;

    let config = await SystemConfig.findByPk(1);
    if (!config) {
      return res.status(500).json({ error: 'Configuración global no inicializada en la BD.' });
    }

    if (maintenanceMode !== undefined) config.maintenanceMode = maintenanceMode;
    if (captchaEnabled !== undefined) config.captchaEnabled = captchaEnabled;
    if (registrationEnabled !== undefined) config.registrationEnabled = registrationEnabled;
    if (passwordRecoveryEnabled !== undefined) config.passwordRecoveryEnabled = passwordRecoveryEnabled;

    await config.save();

    res.status(200).json({
      message: 'Configuración del sistema actualizada correctamente.',
      config: {
        maintenanceMode: config.maintenanceMode,
        captchaEnabled: config.captchaEnabled,
        registrationEnabled: config.registrationEnabled,
        passwordRecoveryEnabled: config.passwordRecoveryEnabled
      }
    });

  } catch (error) {
    gestionarError(res, error, 'Error interno al cambiar la configuración de la plataforma.', 500);

  }
}






exports.updatePassword = async (req, res) => {
  try {
    const userId = req.userId;
    const { currentPassword, newPassword } = req.body;

    if (!currentPassword || !newPassword) {
      return res.status(400).json({ error: 'La contraseña actual y la nueva son obligatorias.' });
    }

    if (typeof newPassword !== 'string' || newPassword.length < 12) {
      return res.status(400).json({ error: 'La nueva contraseña debe tener al menos 12 caracteres.' });
    }

    const user = await User.findByPk(userId);
    if (!user) return res.status(404).json({ error: 'Usuario no encontrado.' });

    const isMatch = await bcrypt.compare(currentPassword, user.password);
    if (!isMatch) {
      return res.status(400).json({ error: 'La contraseña actual es incorrecta.' });
    }


    user.password = newPassword;
    await user.save();

    res.status(200).json({ message: 'Contraseña actualizada con éxito.' });
  } catch (error) {
    gestionarError(res, error, 'Error interno al actualizar la contraseña.', 500);
  }
};






exports.deleteAccount = async (req, res) => {
  try {
    const userId = req.userId;

    const user = await User.findByPk(userId);
    if (!user) return res.status(404).json({ error: 'Usuario no encontrado.' });

    if (user.role === 'admin' && user.username == process.env.DEFAULT_ADMIN_USER) {
      return res.status(403).json({ error: 'La cuenta del administrador principal no puede ser eliminada.' });
    }

    await user.destroy();

    res.status(200).json({ message: 'Tu cuenta ha sido eliminada permanentemente.' });
  } catch (error) {

    gestionarError(res, error, 'Error interno al eliminar la cuenta.', 500);

  }
};