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

const { Op } = require('sequelize');
const { Template } = require('../models/template');
const { gestionarError } = require('../utils/error-helper');
const { User } = require('../models/user');

exports.getTemplates = async (req, res) => {
  try {
    const userId = req.userId;

    const templates = await Template.findAll({
      where: { [Op.or]: [{ userId }, { isPublic: true }] },
      attributes: ['id', 'name', 'options', 'isPublic', 'userId', 'createdAt'],      
      order: [['createdAt', 'DESC']]
    });

    // Las públicas de otros se pueden usar, pero no modificar ni borrar
    res.status(200).json(templates.map(t => {
      const { userId: autor, ...plantilla } = t.toJSON();
      return { ...plantilla, propia: String(autor) === String(userId) };
    }));
  } catch (error) {
    gestionarError(res, error, 'Error al obtener la lista de plantillas.', 500);
  }
};

exports.postTemplate = async (req, res) => {
  try {
    const userId = req.userId;
    const { name, options, isPublic } = req.body;

    if (!name || name.trim() === '') {
      return res.status(400).json({ error: 'El nombre de la plantilla es obligatorio.' });
    }

    // Solo un administrador puede publicar una plantilla para todos (rol consultado en BD)
    if (isPublic === true && (await User.findByPk(userId, { attributes: ['role'] }))?.role !== 'admin') {
      return res.status(403).json({ error: 'Solo un administrador puede crear plantillas públicas.' });
    }


    const newTemplate = await Template.create({
      userId,
      name: name.trim(),
      options: options || {},
      isPublic: isPublic === true
    });

    res.status(201).json({
      message: 'Plantilla guardada con éxito.',
      template: {
        id: newTemplate.id,
        name: newTemplate.name,
        options: newTemplate.options,
        isPublic: newTemplate.isPublic,
        propia: true
      }
    });
  } catch (error) {
    gestionarError(res, error, 'Error interno al guardar la plantilla.', 500);
  }
};



exports.deleteTemplate = async (req, res) => {
  try {
    const userId = req.userId;
    const templateId = req.params.id;

    const template = await Template.findOne({
      where: { id: templateId, userId }
    });

    if (!template) {
      return res.status(404).json({ error: 'Plantilla no encontrada o sin permisos para eliminarla.' });
    }

    await template.destroy();

    res.status(200).json({ message: 'Plantilla eliminada de forma permanente.' });
  } catch (error) {
    gestionarError(res, error, 'Error interno al eliminar la plantilla.', 500);
  }

}



exports.putTemplate = async (req, res) => {
  try {
    const userId = req.userId;
    const templateId = req.params.id;
    const { name, options } = req.body;

    const template = await Template.findOne({
      where: { id: templateId, userId }
    });

    if (!template) {
      return res.status(404).json({ error: 'Plantilla no encontrada o sin permisos.' });
    }

    if (name && name.trim() !== '') {
      template.name = name.trim();
    }

    if (options) {
      template.options = options;
    }

    await template.save();

    res.status(200).json({
      message: 'Plantilla actualizada con éxito.',
      template: {
        id: template.id,
        name: template.name,
        options: template.options
      }
    });
  } catch (error) {
    gestionarError(res, error, 'Error interno al actualizar la plantilla.', 500);
  }
}


