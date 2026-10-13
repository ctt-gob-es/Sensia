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


/**
 * Registra el error en el log y responde al cliente con statusCode y mensajeUsuario.
 * @param {Object} res - Objeto de respuesta de Express
 * @param {Error|String} error - El error original capturado
 * @param {String} mensajeUsuario - (Opcional) Mensaje para el frontend
 * @param {Number} statusCode - (Opcional) Por defecto 500
 */
const gestionarError = (res, error, mensajeUsuario = 'Error interno del servidor', statusCode = 500) => {
    const stack = error instanceof Error ? error.stack : 'No stack trace';
    const mensajeRaiz = error.message || error;

    console.error(`---------- [ERROR LOG] ----------`);
    console.error(`Fecha: ${new Date().toLocaleString()}`);
    console.error(`Mensaje: ${mensajeRaiz}`);
    console.error(`Stack: ${stack}`);
    console.error(`---------------------------------`);

    return res.status(statusCode).json({
        success: false,
        error: mensajeUsuario,
        debug: process.env.NODE_ENV === 'development' ? mensajeRaiz : undefined
    });
};



module.exports = { gestionarError };
