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


const logger = require('./logger');

const requiredEnvVars = [
    'PORT', 'JWT_SECRET', 'DB_STORAGE', 'DIR_LOGS',
    'DEFAULT_ADMIN_USER', 'DEFAULT_ADMIN_PASSWORD',
    'SOAP_API_URL', 'SOAP_API_TOKEN',
    'ALTCHA_HMAC_KEY'
];

const PATRONES_SECRETO = ['SECRET', 'PASS', 'TOKEN', 'KEY'];
const esSecreto = v => !v.endsWith('_URL') && PATRONES_SECRETO.some(p => v.includes(p));
const mostrar = v => (esSecreto(v) ? '********' : process.env[v]);

const optionalEnvVars = [
    'HARD_MAINTENANCE', 'MAX_BODY_SIZE', 'MAX_UPLOAD_MB'
];

function validateEnv() {
    console.log('--- [SensIA] Verificando variables de entorno ---');
    let hasErrors = false;

    requiredEnvVars.forEach(v => {
        if (!process.env[v]) {
            console.error(`[ERROR CRÍTICO] La variable base ${v} no está informada.`);
            hasErrors = true;
        } else {
            logger.info(`${v}: ${mostrar(v)}`);
        }
    });

    optionalEnvVars.forEach(v => {
        if (!process.env[v]) {
            console.warn(`[LOG.WARNING] La variable opcional ${v} no tiene valor.`);
        } else {
            logger.info(`${v}: ${mostrar(v)}`);
        }
    });

    
    const smtpAuthMethod = (process.env.SMTP_AUTH_METHOD || '').toUpperCase();
    let requiredMailVars = [];
    let optionalMailVars = [];

    if (smtpAuthMethod === 'OAUTH2') {
        console.log('Detectada configuración de correo: OAUTH2');
        requiredMailVars = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'EMAIL_FROM', 'OAUTH_CLIENT_ID', 'OAUTH_CLIENT_SECRET', 'OAUTH_REFRESH_TOKEN', 'OAUTH_TOKEN_URL'];
    } else if (smtpAuthMethod === 'PLAIN') {
        console.log('Detectada configuración de correo: PLAIN (Contraseña tradicional)');
        requiredMailVars = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASS', 'EMAIL_FROM'];
    } else if (smtpAuthMethod === 'NONE') {
        console.log('Detectada configuración de correo: NONE (Relay Local Anónimo)');
        requiredMailVars = ['SMTP_HOST', 'SMTP_PORT'];
        optionalMailVars = ['EMAIL_FROM'];
    } else if (smtpAuthMethod) {
        console.error(`[ERROR CRÍTICO CORREO] SMTP_AUTH_METHOD no válido: "${smtpAuthMethod}". Admitidos: PLAIN, OAUTH2, NONE o vacío (sin correo).`);
        hasErrors = true;
    } else {
        console.warn('[LOG.WARNING] Módulo de correo inactivo (SMTP_AUTH_METHOD vacío).');
    }

    
    requiredMailVars.forEach(v => {
        if (!process.env[v]) {
            console.error(`[ERROR CRÍTICO CORREO] La variable ${v} es OBLIGATORIA para el método ${smtpAuthMethod}.`);
            hasErrors = true;
        } else {
            logger.info(`Correo ${v}: ${mostrar(v)}`);
        }
    });

    
    optionalMailVars.forEach(v => {
        if (!process.env[v]) {
            console.warn(`[LOG.WARNING] La variable opcional de correo ${v} no tiene valor.`);
        }
    });

    console.log('--- [SensIA] Verificación finalizada ---\n');

    if (hasErrors) {
        console.error('El sistema no puede arrancar sin las variables críticas o con configuración de correo incompleta. Abortando...');
        process.exit(1); 
    }
}

module.exports = validateEnv;
