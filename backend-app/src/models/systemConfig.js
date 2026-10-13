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


const { DataTypes } = require('sequelize');
const { sequelize } = require('../config/database');



const SystemConfig = sequelize.define('SystemConfig', {
  id: { type: DataTypes.INTEGER, primaryKey: true, autoIncrement: true },
  maintenanceMode: { type: DataTypes.BOOLEAN, defaultValue: false },
  captchaEnabled: { type: DataTypes.BOOLEAN, defaultValue: true },
  registrationEnabled: { type: DataTypes.BOOLEAN, defaultValue: false },
  passwordRecoveryEnabled: { type: DataTypes.BOOLEAN, defaultValue: false }
}, {
  timestamps: true
});



module.exports = { SystemConfig };