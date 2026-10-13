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
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { sendEmail } = require('../utils/mailer');
const { verifySolution, createChallenge } = require('altcha-lib');
const SECRET_KEY = process.env.JWT_SECRET;
const MAX_UPLOAD_MB = process.env.MAX_UPLOAD_MB || 50
const usedAltchaSalts = new Set();
const { gestionarError } = require('../utils/error-helper');
const defaults = require('../config/defaults.json');
const { User } = require('../models/user');
const { SystemConfig } = require('../models/systemConfig');
const logger = require('../utils/logger');



const validateCaptcha = async (altchaString, isCaptchaEnabled) => {

  if (!isCaptchaEnabled) return { isValid: true };

  if (!altchaString) {
    return { isValid: false, error: 'Debes completar la verificación de seguridad' };
  }

  let altchaPayload;
  try {
    const decoded = Buffer.from(altchaString, 'base64').toString('utf8');
    altchaPayload = JSON.parse(decoded);
  } catch (e) {
    return { isValid: false, error: 'Formato de captcha inválido' };
  }

  if (!altchaPayload || typeof altchaPayload.salt !== 'string') {
    return { isValid: false, error: 'Formato de captcha inválido' };
  }

  if (usedAltchaSalts.has(altchaPayload.salt)) {
    return { isValid: false, error: 'El captcha ha caducado o ya fue utilizado. Vuelve a intentarlo.' };
  }

  // antes del await para que dos peticiones simultáneas no usen la misma solución
  usedAltchaSalts.add(altchaPayload.salt);

  const hmacKey = process.env.ALTCHA_HMAC_KEY;
  const isAltchaValid = await verifySolution(altchaString, hmacKey);

  if (!isAltchaValid) {
    usedAltchaSalts.delete(altchaPayload.salt);
    return { isValid: false, error: 'Verificación de seguridad fallida' };
  }

  setTimeout(() => usedAltchaSalts.delete(altchaPayload.salt), 5 * 60 * 1000);

  return { isValid: true };

}




exports.login = async (req, res) => {
  try {
    const { username, password, altcha } = req.body;

    if (!username || !password) {
      return res.status(401).json({ error: 'Usuario y contraseña son obligatorios' });
    }

    const config = await SystemConfig.findByPk(1);
    const isCaptchaEnabled = config ? config.captchaEnabled : false;
    const isMaintenanceMode = config ? config.maintenanceMode : false;

    const captchaValidation = await validateCaptcha(altcha, isCaptchaEnabled);
    if (!captchaValidation.isValid) {
      return res.status(401).json({ error: captchaValidation.error });
    }

    const user = await User.findOne({ where: { username } });

    if (!user) {
      return res.status(401).json({ error: 'El usuario proporcionado o la contraseña son incorrectos.' });
    }

    logger.info(`Intento de login para usuario: ${username} desde IP: ${req.ip}`);

    const passwordIsValid = await bcrypt.compare(password, user.password);
    if (!passwordIsValid) {
      return res.status(401).json({ auth: false, token: null, error: 'El usuario proporcionado o la contraseña son incorrectos.' });
    }

    if (user.isBlocked) {
      return res.status(403).json({ auth: false, token: null, error: 'Cuenta bloqueada temporalmente. Contacta con soporte.' });
    }

    if (process.env.HARD_MAINTENANCE === 'true') {
      return res.status(503).json({ auth: false, token: null, error: 'Sistema fuera de servicio por motivos técnicos. Inténtelo más tarde.' });
    }

    if (isMaintenanceMode && user.role !== 'admin') {
      return res.status(503).json({
        auth: false,
        token: null,
        error: 'Sistema en mantenimiento. Solo administradores pueden acceder ahora.'
      });
    }

    const token = jwt.sign({ id: user.id }, SECRET_KEY, { expiresIn: '24h' });
    
    res.status(200).json({ user: username, role: user.role, auth: true, token});

  } catch (error) {
    gestionarError(res, error, 'Error interno del servidor.', 500);
  }
};




exports.register = async (req, res) => {
  try {
    const config = await SystemConfig.findByPk(1);
    if (config && !config.registrationEnabled) {
      return res.status(403).json({ error: 'El registro de nuevos usuarios está deshabilitado.' });
    }

    const { username, altcha } = req.body;
    const isCaptchaEnabled = config ? config.captchaEnabled : false;

    const captchaValidation = await validateCaptcha(altcha, isCaptchaEnabled);
    if (!captchaValidation.isValid) {
      return res.status(401).json({ error: captchaValidation.error });
    }

    if (!username || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(username)) {
      return res.status(400).json({ error: 'Indica un correo electrónico válido.' });
    }

    let email = username;

    const tempPassword = crypto.randomBytes(4).toString('hex');

    const newUser = await User.create({
      username,
      email,
      password: tempPassword,
      settings: defaults.userSettings
    });

    try {
      await sendEmail(
        email,
        'Registro realizado. Clave de acceso temporal SensIA',
        `<h2>Registro realizado.</h2>
         <p>Tu contraseña temporal es: <strong>${tempPassword}</strong></p>
         <p>Por seguridad, cámbiala en cuanto accedas.</p>`
      );
    } catch (error) {
      // Sin el correo, el usuario no conocería su contraseña: se deshace el alta
      await newUser.destroy();
      logger.error('Registro: no se pudo enviar el correo de alta', error);
      return res.status(503).json({ error: 'No se ha podido enviar el correo de registro. Inténtalo más tarde.' });
    }

    res.status(201).json({ id: newUser.id, message: 'Usuario creado exitosamente' });

  } catch (error) {
    if (error.name === 'SequelizeUniqueConstraintError') {
      return res.status(409).json({ error: 'El nombre de usuario o email ya existe' });
    }
    gestionarError(res, error, 'Error interno del servidor al registrar usuario', 500);

  }
};




exports.status = async (req, res) => {
  try {
    const isHard = process.env.HARD_MAINTENANCE === 'true';

    if (isHard) {
      return res.status(503).json({
        status: 'maintenance',
        type: 'hard',
        message: 'Sistema fuera de servicio técnico.',
        features: { registrationEnabled: false, passwordRecoveryEnabled: false }
      });
    }

    let config = await SystemConfig.findByPk(1);

    const publicConfig = {
      maintenanceMode: config ? config.maintenanceMode : false,
      captchaEnabled: config ? config.captchaEnabled : false,
      registrationEnabled: config ? config.registrationEnabled : false,
      passwordRecoveryEnabled: config ? config.passwordRecoveryEnabled : false,
      maxUploadSizeMB: MAX_UPLOAD_MB
    };


    if (config && config.maintenanceMode) {
      return res.status(200).json({
        status: 'maintenance',
        type: 'soft',
        message: 'La web está en mantenimiento. Solo acceso a administradores.',
        features: publicConfig
      });
    }

    return res.status(200).json({
      status: 'ok',
      features: publicConfig
    });

  } catch (error) {
    return gestionarError(res, error, 'Error al verificar el estado del servidor.', 500);
  }
};






exports.altchaChallenge = async (req, res) => {
  try {
    const hmacKey = process.env.ALTCHA_HMAC_KEY;

    // maxNumber define la "dificultad" ---> 100000 equilibrado entre seguridad y velocidad
    // Caduca a los 5 minutos, no se puede reutilizar después
    const challenge = await createChallenge({
      hmacKey, maxNumber: 100000, expires: new Date(Date.now() + 5 * 60 * 1000)
    });    

    res.json(challenge);

  } catch (error) {
    gestionarError(res, error, 'Error generando el desafío ALTCHA', 500);

  }
};





exports.forgotPasswordOtp = async (req, res) => {
  try {

    const config = await SystemConfig.findByPk(1);

    if (config && !config.passwordRecoveryEnabled) {
      return res.status(403).json({ error: 'La recuperación de contraseñas está deshabilitada temporalmente.' });
    }

    const { username, altcha } = req.body;
    const isCaptchaEnabled = config ? config.captchaEnabled : false;

    const captchaValidation = await validateCaptcha(altcha, isCaptchaEnabled);
    if (!captchaValidation.isValid) {
      return res.status(401).json({ error: captchaValidation.error });
    }

    const genericResponse = { message: 'Si el correo está registrado, recibirás una clave temporal.' };

    const user = await User.findOne({ where: { username } });
    if (!user) return res.status(200).json(genericResponse);

    if (user.isBlocked) {
      return res.status(200).json(genericResponse);
    }

     // Código de un solo uso con caducidad: la contraseña actual no cambia hasta que se usa
    const codigo = crypto.randomBytes(4).toString('hex');
    user.resetPasswordToken = await bcrypt.hash(codigo, 10);
    user.resetPasswordExpires = new Date(Date.now() + 15 * 60 * 1000);
    await user.save();

    await sendEmail(
      user.email,
      'Código de recuperación de contraseña SensIA',
      `<h2>Recuperación de contraseña</h2>
       <p>Tu código temporal es: <strong>${codigo}</strong></p>
       <p>Caduca en 15 minutos. Si no lo has solicitado, ignora este mensaje: tu contraseña no ha cambiado.</p>`
    );

    res.status(200).json(genericResponse);

  } catch (error) {
    logger.error('Recuperación de contraseña: error al procesar la solicitud', error);
    gestionarError(res, error, 'Error al procesar la solicitud. ', 500);
  }
};


exports.resetPassword = async (req, res) => {
  try {
    const { username, tempPassword, newPassword, altcha } = req.body;

    if (!username || !tempPassword || !newPassword) {
      return res.status(400).json({ error: 'Usuario, código temporal y nueva contraseña son obligatorios' });
    }
    
    if (typeof newPassword !== 'string' || newPassword.length < 12) {
      return res.status(400).json({ error: 'La nueva contraseña debe tener al menos 12 caracteres.' });
    }

    const config = await SystemConfig.findByPk(1);
    const isCaptchaEnabled = config ? config.captchaEnabled : false;

    const captchaValidation = await validateCaptcha(altcha, isCaptchaEnabled);
    if (!captchaValidation.isValid) {
      return res.status(401).json({ error: captchaValidation.error });
    }

    const user = await User.findOne({ where: { username } });

    if (!user) {
      return res.status(401).json({ error: 'Datos de recuperación inválidos.' });
    }

    if (user.isBlocked) {
      return res.status(401).json({ error: 'Cuenta bloqueada temporalmente. Contacta con soporte.' });
    }

    const vigente = !!user.resetPasswordToken && user.resetPasswordExpires > new Date();
    const isValidTempPassword = vigente && await bcrypt.compare(tempPassword, user.resetPasswordToken);

    if (!isValidTempPassword) {
      return res.status(401).json({ error: 'El código temporal es incorrecto o ha expirado.' });
    }

    user.password = newPassword;
    user.resetPasswordToken = null;
    user.resetPasswordExpires = null;
    await user.save();

    res.status(200).json({ message: 'Contraseña restablecida con éxito.' });

  } catch (error) {
    gestionarError(res, error, 'Error al restablecer la contraseña.', 500);
  }
};
