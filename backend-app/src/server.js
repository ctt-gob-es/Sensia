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


require('dotenv').config({ quiet: true });
const app = require('./app');
const { initDB } = require('./config/database');
const logger = require('./utils/logger');
const validateEnv = require('./utils/env.validator.js');


async function start() {
  validateEnv();

  const PORT = process.env.PORT || 3000;

  try {
    await initDB(); 
    
    const server = app.listen(PORT, () => {
      logger.info(`Servidor SensIA app corriendo en puerto ${PORT}`);
    });

    process.on('SIGTERM', () => {
      server.close(() => process.exit(0));
    });
   
    server.on('error', (error) => {
      if (error.code === 'EADDRINUSE') {
        logger.error(`Fallo de arranque: El puerto ${PORT} ya está en uso. Cierra la otra consola o programa que lo esté usando.`);
        process.exit(1);
      } else {
        throw error;
      }
    });

  } catch (err) {
    logger.error("Error al arrancar:", err);
    process.exit(1);
  }
  
} 


start();
