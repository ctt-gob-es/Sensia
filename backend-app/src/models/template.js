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

const Template = sequelize.define('Template', {
  name: { 
    type: DataTypes.STRING, 
    allowNull: false 
  },
  options: {
    type: DataTypes.JSON,
    allowNull: false,
    defaultValue: {}
  },
  isPublic: {
    type: DataTypes.BOOLEAN,
    allowNull: false,
    defaultValue: false
  },
  userId: {
    type: DataTypes.INTEGER, 
    allowNull: false,
    references: {
      model: 'Users', 
      key: 'id'
    },
    onUpdate: 'CASCADE',
    onDelete: 'CASCADE'
  }
});

module.exports = { Template };