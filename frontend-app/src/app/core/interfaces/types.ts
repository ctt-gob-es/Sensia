/*
 * Este archivo es parte del proyecto SensIA.
 * Repositorio oficial: https://github.com/ctt-gob-es/sensia
 *
 * SensIA se distribuye bajo los términos de la Licencia Pública 
 * de la Unión Europea (EUPL) v1.2
 *
 * Consulta el archivo LICENSE en la raíz del proyecto para ver los 
 * detalles legales completos, o visita:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */


// ============================================================================
// BLOQUE 1: SOAP - ENUMERADOS (WSDL de SenSIA)
// ============================================================================

export enum ProcessStateEnum {
  QUEUED = 'QUEUED',
  PROCESSING = 'PROCESSING',
  COMPLETED = 'COMPLETED',
  NOT_FOUND = 'NOT_FOUND',
  AUTH_ERROR = 'AUTH_ERROR',
  INVALID_DOCUMENT_ERROR = 'INVALID_DOCUMENT_ERROR',
  HASH_MISMATCH_ERROR = 'HASH_MISMATCH_ERROR',
  ERROR = 'ERROR'
}

export enum ActionStateEnum {
  REDACTED = 'REDACTED',
  WHITELISTED = 'WHITELISTED',
  EXCLUDED_ZONE = 'EXCLUDED_ZONE',
  REMOVED = 'REMOVED'
}

export enum StatusEnum {
  SUCCESS = 'SUCCESS',
  WARNING = 'WARNING',
  ERROR = 'ERROR'
}

export enum DetectionTypeEnum {
  SENSITIVE_DATA = 'SENSITIVE_DATA',
  PERSON_NAME = 'PERSON_NAME',
  NAMED_ENTITY = 'NAMED_ENTITY',
  IMAGE = 'IMAGE',
  FIXED_ZONE = 'FIXED_ZONE',
  FACE = 'FACE',
  QR_CODE = 'QR_CODE'
}


// ============================================================================
// BLOQUE 2: SOAP - TIPOS Y MENSAJES PUROS (WSDL de SenSIA)
// ============================================================================

// --- Tipos Base y Opciones ---
export interface Coordinate {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface CoordinatesList {
  coordinate: Coordinate[];
}

export interface WordList {
  words: string[];
}

export interface DocumentRedactionOptions {
  redactQRCodes?: boolean;
  redactNames?: boolean;
  redactNERNames?: boolean;
  redactNEROrg?: boolean;
  redactNERLoc?: boolean;
  redactNERMisc?: boolean;
  redactNERScore?: number;
  removeImages?: boolean;
  preserveLogo?: boolean;
  logoExclusionZone?: Coordinate;
  imageExclusionZones?: CoordinatesList;
  fixedRedactionZones?: CoordinatesList;
  allowedWords?: WordList;
  blockedWords?: WordList;
  redactionThickness?: number;
  usePseudonyms?: boolean;
}

export interface FaceMaskingOptions {
  maskFaces?: boolean;
  minFaceAspectRatio?: number;
  maxFaceAspectRatio?: number;
  minFaceArea?: number;
  maxFaceArea?: number;
  pixelationIntensity?: number;
}

export interface YunetOptions {
  scoreThreshold?: number;
  nmsThreshold?: number;
  topK?: number;
}

export interface ProcessingOptions {
  documentOptions?: DocumentRedactionOptions;
  faceOptions?: FaceMaskingOptions;
  yunetOptions?: YunetOptions; 
  redactionPreview?: boolean;
  reconstructionMode?: boolean;
}

export interface DocumentItem {
  fileName: string;
  contentType?: string;
  fileContent: string; // base64
}

export interface BatchDocumentItem {
  clientId: string;
  document: DocumentItem;
  confirmedRedactions?: RedactionItem[]; 
}

export interface RedactionItem {
  detectionType: DetectionTypeEnum;
  action: ActionStateEnum;
  description?: string;
  text?: string;
  page?: number;
  coordinates: Coordinate;
  sourceDocumentHash?: string;
  apply?: boolean;
  subType?: string;
  pseudonym?: string;
}

export interface BatchTicketItem {
  clientId: string;
  serverTicketId: string;
}

export interface ServiceStatus {
  result: StatusEnum;
  message: string;
  cause?: string;
}

export interface TaskStatus {
  processState?: ProcessStateEnum;
  message?: string;
}

export interface ServiceFaultType {
  errorCode: string;
  errorMessage: string;
}

export interface SubmitBatchRequest {
  options: ProcessingOptions;
  documents: BatchDocumentItem[];
}

export interface SubmitBatchResponse {
  status: ServiceStatus;
  tickets?: BatchTicketItem[]; 
}

export interface GetStatusRequest {
  ticketId: string;
}

export interface GetStatusResponse {
  ticketId: string;
  processedDocument?: DocumentItem;
  redactionResults?: RedactionItem[]; 
  taskStatus: TaskStatus;
  status: ServiceStatus;
}


// ============================================================================
// BLOQUE 3: UI + PROXY - MODELOS PARA ANGULAR
// ============================================================================

export enum DocState {
  PENDING = 'PENDING',
  UPLOADING = 'UPLOADING',
  POLLING_PREVIEW = 'POLLING_PREVIEW',
  REVIEW_READY = 'REVIEW_READY',
  POLLING_FINAL = 'POLLING_FINAL',
  COMPLETED = 'COMPLETED',
  ERROR = 'ERROR'
}

/** Fase intermedia que informa getStatus mientras el documento está en el servidor */
export type FaseServicio = 'EN_COLA' | 'PROCESANDO';

export interface DocumentItemUI {
  id: string;
  file: File;
  contentType: string;
  originalBase64: string;
  status: DocState;
  serverTicketId?: string;
  resultMessage?: string;
  avisoMessage?: string;
  censoredItems?: RedactionItem[];
  finalBase64?: string;
  options: ProcessingOptions;
  // Fase de getStatus mientras se procesa en el servidor
  estadoServicio?: FaseServicio;
  // La revisión ha cambiado el documento (vista previa, análisis o confirmación)
  modificadoEnRevision?: boolean;
  descargado?: boolean;
}


export interface RedactionTemplate {
  id: number;
  name: string;
  options: ProcessingOptions;
  isPublic?: boolean;
  /** La creó el usuario actual; las públicas de otros no se pueden modificar ni borrar */
  propia?: boolean;
  createdAt?: string;
}

