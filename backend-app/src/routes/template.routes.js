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


const express = require('express');
const router = express.Router();
const templateController = require('../controllers/template.controller');
const { verifyToken } = require('../middlewares/auth.middleware');


router.get('/', verifyToken, templateController.getTemplates);
router.post('/', verifyToken, templateController.postTemplate);
router.delete('/:id', verifyToken, templateController.deleteTemplate);
router.put('/:id', verifyToken, templateController.putTemplate);




module.exports = router;