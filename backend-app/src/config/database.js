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


const { Sequelize, QueryTypes } = require('sequelize')
const defaults = require('./defaults.json');

const sequelize = new Sequelize({
  dialect: 'sqlite',
  storage: process.env.DB_STORAGE || '../bdd/database.db',
  logging: false
});

const initDB = async (options = {}) => {
  try {
    const alter = process.env.DB_SYNC_ALTER === 'true';
    await sequelize.query('DROP TABLE IF EXISTS Users_backup;');

    if (alter) await sequelize.query('PRAGMA foreign_keys = OFF;');
    try {
      await sequelize.sync(alter ? { alter: true } : {});
    } finally {
      if (alter) await sequelize.query('PRAGMA foreign_keys = ON;');
    }

    if (alter) {
      const violaciones = await sequelize.query('PRAGMA foreign_key_check;', { type: QueryTypes.SELECT });
      if (violaciones.length) {
        throw new Error(`Referencias rotas tras el alter: ${JSON.stringify(violaciones)}`);
      }
    }

    const { User } = require('../models/user');
    const { Template } = require('../models/template');
    const { SystemConfig } = require('../models/systemConfig');

    User.hasMany(Template, { foreignKey: 'userId', as: 'templates' });
    Template.belongsTo(User, { foreignKey: 'userId', as: 'user' });

    if (!process.env.DEFAULT_ADMIN_PASSWORD
        && !(await User.count({ where: { username: process.env.DEFAULT_ADMIN_USER } }))) {
      throw new Error('Falta DEFAULT_ADMIN_PASSWORD para crear el administrador inicial.');
    }
    const [admin, createdUser] = await User.findOrCreate({
      where: { username: process.env.DEFAULT_ADMIN_USER },
      defaults: {
        email: process.env.DEFAULT_ADMIN_USER,
        password: process.env.DEFAULT_ADMIN_PASSWORD, // la cifra el hook beforeCreate de User
        role: 'admin',
        settings: defaults.userSettings
      }
    });

    if (createdUser) console.log('Usuario admin por defecto creado (contraseña en .env --> DEFAULT_ADMIN_PASSWORD)');

    const [config, createdConfig] = await SystemConfig.findOrCreate({
      where: { id: 1 },
      defaults: defaults.systemConfig
    });

    if (createdConfig) console.log('Configuración global del sistema inicializada.');

    console.log('Base de datos SQLite conectada y sincronizada.');
  } catch (error) {
    console.error('Error al conectar con la base de datos:', error.message);
    throw error;
  }
};

module.exports = { sequelize, initDB };
