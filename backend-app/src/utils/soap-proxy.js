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


const crypto = require('crypto');
const express = require('express');
const axios = require('axios');
const convert = require('xml-js');
const logger = require('./logger');

const router = express.Router();

const SOAP_API_URL = process.env.SOAP_API_URL;
const SOAP_API_TOKEN = process.env.SOAP_API_TOKEN;

if (!SOAP_API_URL) {
  logger.error("ERROR: No se ha definido la URL del servicio SOAP en las variables de entorno.");
}


// ---------------------------------------------------------------------------
// Lectura de las respuestas SOAP (xml-js en modo compacto)
// ---------------------------------------------------------------------------

const textoNodo = (field) => {
  if (field === null || field === undefined) return null;
  if (typeof field !== 'object') return String(field).trim() || null;
  const partes = [field._text, field._cdata].flat().filter(p => p !== undefined && p !== null);
  return partes.length > 0 ? (partes.join('').trim() || null) : null;
};

const getTexto = (field) => textoNodo(field);

const getVal = (field) => {
  const texto = textoNodo(field);
  if (texto === null) return null;
  const minusculas = texto.toLowerCase();
  if (minusculas === 'true') return true;
  if (minusculas === 'false') return false;
  if (!isNaN(texto)) return Number(texto);
  return texto;
};


// ---------------------------------------------------------------------------
// Construcción de las peticiones SOAP
// ---------------------------------------------------------------------------

// Escapa un valor para insertarlo como texto en el XML (nombres de archivo, palabras de las listas...)
const escaparXml = (valor) => {
  const texto = String(valor);
  if (!/[&<>"']/.test(texto)) return texto;
  return texto
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
};


// Etiqueta con un valor simple (texto, número o booleano). No se escribe si no hay valor.
const etiqueta = (nombre, valor) => {
  if (valor === undefined || valor === null || valor === '' || typeof valor === 'object') return '';
  return `<${nombre}>${escaparXml(valor)}</${nombre}>`;
};


// Etiqueta con el texto en CDATA, válida aunque el texto contenga «]]>».
const etiquetaCdata = (nombre, valor) => {
  if (typeof valor !== 'string' && typeof valor !== 'number') return '';
  const texto = String(valor);
  if (texto === '') return '';
  return `<${nombre}><![CDATA[${texto.replace(/]]>/g, ']]]]><![CDATA[>')}]]></${nombre}>`;
};


// Coordenada (x, y, w, h) en XML
const buildCoordinateXml = (tagName, c) => {
  if (!c) return '';
  const { x, y, w, h } = c;
  if ([x, y, w, h].some(v => v === undefined || v === null || v === '' || isNaN(v))) {
    return '';
  }
  return `<${tagName}><x>${Number(x)}</x><y>${Number(y)}</y><w>${Number(w)}</w><h>${Number(h)}</h></${tagName}>`;
};


// Lista de coordenadas en XML
const buildCoordinatesListXml = (tagName, coordsListObj) => {
  if (!coordsListObj || !Array.isArray(coordsListObj.coordinate) || coordsListObj.coordinate.length === 0) {
    return '';
  }
  const coordsTags = coordsListObj.coordinate.map(c => buildCoordinateXml('coordinate', c)).join('');
  return coordsTags ? `<${tagName}>${coordsTags}</${tagName}>` : '';
};


// Lista de palabras en XML
const buildWordListXml = (tagName, wordListObj) => {
  if (!wordListObj || !Array.isArray(wordListObj.words) || wordListObj.words.length === 0) {
    return '';
  }
  const wordsTags = wordListObj.words.map(word => etiqueta('words', word)).join('');
  return wordsTags ? `<${tagName}>${wordsTags}</${tagName}>` : '';
};


// Marca confirmada por el usuario. Un solo detectionType; subType solo se envía en modo
// reconstrucción y solo para SENSITIVE_DATA.
const buildConfirmedRedactionXml = (redaction, esReconstruccion) => {
  const subtipo = esReconstruccion
    && redaction.detectionType === 'SENSITIVE_DATA'
    && typeof redaction.subType === 'string'
    ? redaction.subType.trim()
    : '';

  return `
          <confirmedRedactions>
            ${etiqueta('detectionType', redaction.detectionType)}
            ${etiqueta('subType', subtipo)}
            ${etiqueta('action', redaction.action)}
            ${etiquetaCdata('description', redaction.description)}
            ${etiquetaCdata('text', redaction.text)}
            ${redaction.page ? etiqueta('page', redaction.page) : ''}
            ${buildCoordinateXml('coordinates', redaction.coordinates)}
            ${etiqueta('sourceDocumentHash', redaction.sourceDocumentHash)}
            ${etiqueta('apply', redaction.apply)}
            ${etiqueta('pseudonym', redaction.pseudonym)}
          </confirmedRedactions>
        `;
};



// Log de sanitización para no imprimir bases64
//const logSoapRequest = (xmlString, action) => {
//  const cleanedXml = xmlString.replace(
//    /(<fileContent>)([\s\S]*?)(<\/fileContent>)/g,
//    (match, openTag, content, closeTag) => {
//      return `${openTag} [DATA_HIDDEN_SIZE: ${content.length} chars] ${closeTag}`;
//    }
//  );
//  //logger.debug(`--- SOAP Request: ${action} ---`);
//  //logger.debug(cleanedXml);
//  //logger.debug("--------------------");
//};




// ---------------------------------------------------------------------------
// Reparto del servicio entre usuarios
// ---------------------------------------------------------------------------

// Valor de status/cause con el que el servicio rechaza un lote por estar lleno (ProcessState.SATURADO)
const CODIGO_SATURADO = 'SATURADO';

// Clave del usuario para AuthHeader/cliente (obligatoria en el servicio). req.userId lo pone verifyToken,
// que app.js aplica a todo /api. Es un HMAC con el secreto del proxy: el servicio distingue usuarios
// sin poder saber quiénes son (un hash simple de un id numérico se revierte probando ids).
const claveCliente = (req) => {
  if (req.userId === undefined || req.userId === null) return null;
  return crypto.createHmac('sha256', process.env.JWT_SECRET).update(`sensia-cliente:${req.userId}`).digest('hex');
};

// Sin usuario no se puede llamar al servicio (no debería ocurrir: verifyToken va antes)
const sinUsuario = (res) => res.status(401).json({ auth: false, message: 'La sesión ya no es válida.' });



// Fallo SOAP con el que el servicio indica que está saturado
const esSaturado = (error) => {
  const xml = error.response?.data;
  if (typeof xml !== 'string') return false;
  try {
    const json = convert.xml2js(xml, { compact: true, ignoreComment: true, elementNameFn: (v) => v.split(':').pop() });
    return getTexto(json.Envelope?.Body?.Fault?.detail?.ServiceFault?.errorCode) === CODIGO_SATURADO;
  } catch {
    return false;
  }
};




router.post('/submit-batch', async (req, res) => {
  // Angular envía la estructura:
  // { options: { documentOptions: {...}, faceOptions: {...}, yunetOptions: {...},
  //              redactionPreview: boolean, reconstructionMode: boolean },
  //   documents: [ { clientId, document: { fileName, contentType, fileContent }, confirmedRedactions?: [...] } ] }

  const cliente = claveCliente(req);
  if (!cliente) return sinUsuario(res);

  const { options = {}, documents = [] } = req.body;
  const esReconstruccion = String(options.reconstructionMode) === 'true';

  let documentOptionsXml = '';
  if (options.documentOptions) {
    const dOpt = options.documentOptions;
    documentOptionsXml = `
  <documentOptions>
    ${etiqueta('redactQRCodes', dOpt.redactQRCodes)}
    ${etiqueta('redactNames', dOpt.redactNames)}
    ${etiqueta('redactNERNames', dOpt.redactNERNames)}
    ${etiqueta('redactNEROrg', dOpt.redactNEROrg)}
    ${etiqueta('redactNERLoc', dOpt.redactNERLoc)}
    ${etiqueta('redactNERMisc', dOpt.redactNERMisc)}
    ${etiqueta('redactNERScore', dOpt.redactNERScore)}
    ${etiqueta('removeImages', dOpt.removeImages)}
    ${etiqueta('preserveLogo', dOpt.preserveLogo)}
    ${buildCoordinateXml('logoExclusionZone', dOpt.logoExclusionZone)}
    ${buildCoordinatesListXml('imageExclusionZones', dOpt.imageExclusionZones)}
    ${buildCoordinatesListXml('fixedRedactionZones', dOpt.fixedRedactionZones)}
    ${buildWordListXml('allowedWords', dOpt.allowedWords)}
    ${buildWordListXml('blockedWords', dOpt.blockedWords)}
    ${etiqueta('redactionThickness', dOpt.redactionThickness)}
    ${etiqueta('usePseudonyms', dOpt.usePseudonyms)}
  </documentOptions>
`;
  }

  let faceOptionsXml = '';
  if (options.faceOptions) {
    const fOpt = options.faceOptions;
    faceOptionsXml = `
      <faceOptions>
        ${etiqueta('maskFaces', fOpt.maskFaces)}
        ${etiqueta('minFaceAspectRatio', fOpt.minFaceAspectRatio)}
        ${etiqueta('maxFaceAspectRatio', fOpt.maxFaceAspectRatio)}
        ${etiqueta('minFaceArea', fOpt.minFaceArea)}
        ${etiqueta('maxFaceArea', fOpt.maxFaceArea)}
        ${etiqueta('pixelationIntensity', fOpt.pixelationIntensity)}
      </faceOptions>
    `;
  }


  let yunetOptionsXml = '';
  const yOpt = options.yunetOptions;
  const yunetCompleto = !!yOpt && ['scoreThreshold', 'nmsThreshold', 'topK']
    .every(k => yOpt[k] !== null && yOpt[k] !== undefined && yOpt[k] !== '' && Number.isFinite(Number(yOpt[k])));
  if (yunetCompleto) {
    yunetOptionsXml = `
      <yunetOptions>
        ${etiqueta('scoreThreshold', yOpt.scoreThreshold)}
        ${etiqueta('nmsThreshold', yOpt.nmsThreshold)}
        ${etiqueta('topK', yOpt.topK)}
      </yunetOptions>
    `;
  }

  const redactionPreviewXml = etiqueta('redactionPreview', options.redactionPreview);
  const reconstructionModeXml = etiqueta('reconstructionMode', options.reconstructionMode);

  const docsXml = documents.map(docWrapper => {
    const docData = docWrapper.document ? docWrapper.document : docWrapper;

    const confirmedRedactionsXml = Array.isArray(docWrapper.confirmedRedactions)
      ? docWrapper.confirmedRedactions.map(r => buildConfirmedRedactionXml(r, esReconstruccion)).join('')
      : '';

    return `
      <documents>
        ${etiqueta('clientId', docWrapper.clientId)}
        <document>
          ${etiqueta('fileName', docData.fileName)}
          ${etiqueta('contentType', docData.contentType || 'application/pdf')}
          ${etiqueta('fileContent', docData.fileContent)}
        </document>
        ${confirmedRedactionsXml}
      </documents>
    `;
  }).join('');

  const envelope = `<?xml version="1.0" encoding="UTF-8"?>
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://sensia.github.io/">
      <soapenv:Header>
        <tns:AuthHeader>
          ${etiqueta('token', SOAP_API_TOKEN)}
          ${etiqueta('cliente', claveCliente(req))}
        </tns:AuthHeader>
      </soapenv:Header>
      <soapenv:Body>
        <tns:submitBatchRequest>
          <options>
            ${documentOptionsXml}
            ${faceOptionsXml}
            ${yunetOptionsXml}
            ${redactionPreviewXml}
            ${reconstructionModeXml}
          </options>
          ${docsXml}
        </tns:submitBatchRequest>
      </soapenv:Body>
    </soapenv:Envelope>
  `;

  //logSoapRequest(envelope, 'submitBatch');

  try {
    const response = await axios.post(SOAP_API_URL, envelope, {
      headers: { 'Content-Type': 'text/xml; charset=utf-8', 'SOAPAction': 'http://sensia.github.io/SensiaService/submitBatch' }
    });

    const xmlOptions = { compact: true, ignoreComment: true, elementNameFn: (val) => val.split(':').pop() };
    const jsonResponse = convert.xml2js(response.data, xmlOptions);

    const responseData = jsonResponse.Envelope.Body.submitBatchResponse;

    const result = {
      status: {
        result: getVal(responseData?.status?.result),
        message: getTexto(responseData?.status?.message),
        cause: getTexto(responseData?.status?.cause)
      },
      tickets: []
    };

    if (responseData && responseData.tickets) {
      const ticketsRaw = responseData.tickets;
      const ticketsArray = Array.isArray(ticketsRaw) ? ticketsRaw : [ticketsRaw];

      result.tickets = ticketsArray.map(t => ({
        clientId: getTexto(t.clientId),
        serverTicketId: getTexto(t.serverTicketId)
      }));
    }

    if (result.status.result === 'ERROR' && result.status.cause === CODIGO_SATURADO) {
      // El cliente Angular deja el documento en espera y lo reenvía (esSaturado en estado-documento.ts)
      logger.warn(`submitBatch rechazado: ${result.status.message}`);
      res.set('Retry-After', '5');
      return res.status(503).json({ error: 'Servicio saturado. Reintente más tarde.' });
    }


    res.json(result);

  } catch (error) {
    if (esSaturado(error)) {
      logger.warn('submitBatch rechazado: servicio saturado');
      res.set('Retry-After', '5');
      return res.status(503).json({ error: 'Servicio saturado. Reintente más tarde.' });
    }
    logger.error(`Error in SOAP submitBatch: ${error.message}`);
    res.status(500).json({ error: 'Error communicating with SOAP service'});
  }
});






router.get('/status/:ticketId', async (req, res) => {
  const { ticketId } = req.params;
  const cliente = claveCliente(req);
  if (!cliente) return sinUsuario(res);

  const envelope = `<?xml version="1.0" encoding="UTF-8"?>
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:tns="http://sensia.github.io/">
      <soapenv:Header>
        <tns:AuthHeader>
          ${etiqueta('token', SOAP_API_TOKEN)}
          ${etiqueta('cliente', claveCliente(req))}
        </tns:AuthHeader>
      </soapenv:Header>
      <soapenv:Body>
        <tns:getStatusRequest>
          ${etiqueta('ticketId', ticketId)}
        </tns:getStatusRequest>
      </soapenv:Body>
    </soapenv:Envelope>
  `;

  try {
    const response = await axios.post(SOAP_API_URL, envelope, {
      headers: { 'Content-Type': 'text/xml; charset=utf-8', 'SOAPAction': 'http://sensia.github.io/SensiaService/getStatus' }
    });

    const xmlOptions = { compact: true, ignoreComment: true, elementNameFn: (val) => val.split(':').pop() };
    //const contentType = response.headers['content-type'] || '';
    //logger.debug(`SOAP Content-Type: ${contentType}`);
    //logger.debug(`SOAP response length: ${Buffer.byteLength(String(response.data))}`);
    //logger.debug(`SOAP usa XOP: ${String(response.data).includes('xop:Include')}`);
    //logger.debug(`SOAP multipart: ${contentType.includes('multipart/related')}`);

    const jsonResponse = convert.xml2js(response.data, xmlOptions);

    const statusResponse = jsonResponse.Envelope.Body.getStatusResponse;
    
    if (!statusResponse) {
      throw new Error("No se encontró getStatusResponse en el Body");
    }

    const result = {
      ticketId: getTexto(statusResponse.ticketId),
      taskStatus: {
        processState: getTexto(statusResponse.taskStatus?.processState),
        message: getTexto(statusResponse.taskStatus?.message)
      },
      status: {
        result: getVal(statusResponse.status?.result),
        message: getTexto(statusResponse.status?.message),
        cause: getTexto(statusResponse.status?.cause)
      }
    };

    if (statusResponse.processedDocument) {
      result.processedDocument = {
        fileName: getTexto(statusResponse.processedDocument.fileName),
        contentType: getTexto(statusResponse.processedDocument.contentType),
        fileContent: getTexto(statusResponse.processedDocument.fileContent)
      };
    }

    if (statusResponse.redactionResults) {
      const itemsRaw = statusResponse.redactionResults;
      const itemsArray = Array.isArray(itemsRaw) ? itemsRaw : [itemsRaw];
      
      result.redactionResults = itemsArray.map(item => {
        const pagina = getVal(item.page);
        const aplicar = getVal(item.apply);

        const mappedItem = {
          detectionType: getTexto(item.detectionType),
          subType: getTexto(item.subType),
          action: getTexto(item.action),
          description: getTexto(item.description),
          text: getTexto(item.text),
          page: pagina ? Number(pagina) : undefined,
          sourceDocumentHash: getTexto(item.sourceDocumentHash),
          apply: aplicar !== null ? aplicar : true,
          pseudonym: getTexto(item.pseudonym)
        };

        if (item.coordinates) {
          mappedItem.coordinates = {
            x: parseFloat(getVal(item.coordinates.x)),
            y: parseFloat(getVal(item.coordinates.y)),
            w: parseFloat(getVal(item.coordinates.w)),
            h: parseFloat(getVal(item.coordinates.h))
          };
        }

        return mappedItem;
      });
    }

    res.json(result);

  } catch (error) {
    logger.error(`Error in SOAP status check for ticket ${ticketId}: ${error.message}`);
    res.status(500).json({ error: 'Error communicating with SOAP service'});
  }
});


module.exports = router;
