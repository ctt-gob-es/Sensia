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


const nodemailer = require('nodemailer');
const logger = require('./logger');

const authMethod = (process.env.SMTP_AUTH_METHOD || '').toUpperCase();
const getAuthConfig = () => {
  if (authMethod === 'NONE') {
    return undefined;
  }

  if (authMethod === 'OAUTH2') {
    const auth = {
      type: 'OAuth2',
      user: process.env.SMTP_USER,
      clientId: process.env.OAUTH_CLIENT_ID,
      clientSecret: process.env.OAUTH_CLIENT_SECRET,
      refreshToken: process.env.OAUTH_REFRESH_TOKEN,
    };

    if (process.env.OAUTH_TOKEN_URL) {
      auth.accessUrl = process.env.OAUTH_TOKEN_URL;
    }

    if (process.env.OAUTH_SCOPE) {
      auth.customParams = {
        scope: process.env.OAUTH_SCOPE,
      };
    }

    return auth;
  }

  return {
    user: process.env.SMTP_USER,
    pass: process.env.SMTP_PASS,
  };
};


const transportOptions = {
  host: process.env.SMTP_HOST || 'localhost',
  port: parseInt(process.env.SMTP_PORT || '587', 10),
  secure: process.env.SMTP_SECURE === 'true',
  tls: {
    minVersion: 'TLSv1.2'
  },
};

const authConfig = getAuthConfig();
if (authConfig) {
  transportOptions.auth = authConfig;
}

if (authMethod === 'NONE') {
  transportOptions.ignoreTLS = true;
}
else if (!transportOptions.secure && process.env.SMTP_REQUIRE_TLS !== 'false') {
  transportOptions.requireTLS = true;
}
else if (!transportOptions.secure) {
  logger.warn('SMTP_REQUIRE_TLS=false: las credenciales SMTP pueden viajar sin cifrar. Solo para desarrollo.');
}

const transporter = authMethod ? nodemailer.createTransport(transportOptions) : null;

if (!transporter) {
  logger.warn('Correo desactivado (SMTP_AUTH_METHOD vacío): no se enviarán emails.');
} else{ 
  transporter.verify()
  .then(() => {
    let authLabel = 'Contraseña estándar';
    if (authMethod === 'OAUTH2') authLabel = 'OAuth2';
    if (authMethod === 'NONE') authLabel = 'Sin autenticación (Relay Local)';
    
    logger.info(`Servidor SMTP conectado (${authLabel}) y listo para enviar correos.`);
  })
  .catch((error) => {
    logger.error(`Error al conectar con el servidor SMTP: ${error.message}`);
  });
}

/**
 * Envía un correo en HTML.
 * @param {string} to - Email del destinatario
 * @param {string} subject - Asunto del correo
 * @param {string} htmlContent - Cuerpo del mensaje en formato HTML
 */
const sendEmail = async (to, subject, htmlContent) => {
  if (!transporter) {
    throw new Error('El envío de correo no está configurado (SMTP_AUTH_METHOD vacío).');
  }
  try {
    const info = await transporter.sendMail({
      from: process.env.EMAIL_FROM || process.env.SMTP_USER || 'sensia@localhost',
      to,
      subject,
      html: htmlContent,
    });

    logger.info(`Correo enviado exitosamente a ${to} (ID: ${info.messageId})`);
    return info;
  } catch (error) {
    logger.error(`Error enviando correo a ${to}: ${error.message}`);
    throw error; 
  }
};

module.exports = { sendEmail };