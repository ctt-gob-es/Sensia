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
const authController = require('../controllers/auth.controller');



router.post('/login', authController.login);

router.post('/register', authController.register);

router.get('/status', authController.status);

router.get('/altcha-challenge', authController.altchaChallenge);

router.post('/forgot-password-otp', authController.forgotPasswordOtp);

router.post('/reset-password', authController.resetPassword);

module.exports = router;