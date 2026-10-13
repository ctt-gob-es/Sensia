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
const userController = require('../controllers/user.controller');
const { verifyToken, verifyAdmin } = require('../middlewares/auth.middleware');




router.get('/profile', verifyToken, userController.getProfile);

router.get('/profile/defaults', verifyToken, userController.getDefaultSettings);

router.put('/profile', verifyToken, userController.putProfile);

router.get('/users', verifyToken, verifyAdmin, userController.getUsers);

router.post('/users', verifyToken, verifyAdmin, userController.postUsers);

router.put('/users/:id', verifyToken, verifyAdmin,  userController.putUsers);

router.delete('/users/:id',  verifyToken, verifyAdmin, userController.deleteUsers);

router.put('/users/:id/block', verifyToken, verifyAdmin, userController.blockUsers);

router.put('/config', verifyToken, verifyAdmin, userController.putSystemConfig);

router.put('/profile/password', verifyToken, userController.updatePassword);

router.delete('/profile', verifyToken, userController.deleteAccount);



module.exports = router;