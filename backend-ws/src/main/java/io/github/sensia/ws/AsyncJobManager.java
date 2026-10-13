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

package io.github.sensia.ws;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;

import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import io.github.sensia.processor.CensorResources;
import io.github.sensia.processor.ImageUtils;
import io.github.sensia.processor.SensiaException;
import io.github.sensia.processor.img.FaceDetector;
import io.github.sensia.processor.model.JobInfo;
import io.github.sensia.processor.model.ProcessState;
import io.github.sensia.processor.model.RedactionResult;
import io.github.sensia.processor.model.config.DocumentInfo;
import io.github.sensia.processor.model.config.PdfProcessorContext;
import io.github.sensia.processor.model.dto.DocumentDTO;
import io.github.sensia.processor.model.dto.JobResultDTO;
import io.github.sensia.processor.model.dto.RedactionItemDTO;
import io.github.sensia.processor.model.dto.TaskStatusDTO;
import io.github.sensia.processor.pdf.PdfCensor;


public class AsyncJobManager {
    private static final Logger log = LoggerFactory.getLogger(AsyncJobManager.class);

    // caducidad: 30 minutos
    private static final long TIEMPO_EXPIRACION_MS = 30 * 60 * 1000;

    // Mapa seguro para hilos. ID = Información del Job
    private final Map<String, JobInfo> repositorioTrabajos = new ConcurrentHashMap<>();

    // Ejecutor para procesar los PDFs (Workers)
    // (MAX_EN_COLA + 4) × tamaño máximo de documento debe caber en el heap
    private static final int MAX_EN_COLA = 20;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_EN_COLA));

    public int plazasLibres() {
        return workers.getQueue().remainingCapacity();
    }

    // Trabajos en cola o en proceso que puede tener cada cliente, para que nadie ocupe toda la cola
    public static final int MAX_POR_CLIENTE = 2;
    private final Map<String, Integer> activosPorCliente = new ConcurrentHashMap<>();

    /** Plazas que le quedan al cliente. Sin cliente no hay límite propio, solo el global */
    public int plazasLibres(String cliente) {
        if (cliente == null) return Integer.MAX_VALUE;
        return MAX_POR_CLIENTE - activosPorCliente.getOrDefault(cliente, 0);
    }


    // Ejecutor para el "Basurero". Limpieza automática
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private static final AsyncJobManager INSTANCE = new AsyncJobManager();

    private AsyncJobManager() {
        // Iniciar tarea de limpieza automática: Se ejecuta cada 5 minutos
        scheduler.scheduleAtFixedRate(this::limpiarTrabajosCaducados, 5, 5, TimeUnit.MINUTES);
    }

    public static AsyncJobManager getInstance() {
        return INSTANCE;
    }

    /**
     * Recibe un documento, crea un ticket y lo manda a la cola.
     *
     * @param cliente clave del usuario final (AuthHeader/cliente, obligatoria; la exige AuthTokenInterceptor)
     * @throws SensiaException si la cola está llena o el cliente no tiene plazas
     */
    public String encolarTrabajo(DocumentDTO doc, PdfProcessorContext context, CensorResources recursos, String cliente) {    
        Objects.requireNonNull(cliente, "cliente");
        String ticketId = UUID.randomUUID().toString();

        TaskStatusDTO estadoInit = new TaskStatusDTO(ProcessState.QUEUED, "Esperando worker...");
        String inputHash = ImageUtils.calcularSHA256(doc.fileContent());

        repositorioTrabajos.put(ticketId,
                new JobInfo(estadoInit, doc.fileName(), doc.contentType(), inputHash, doc.confirmedRedactions()));

        if (!reservarPlaza(cliente)) {
            repositorioTrabajos.remove(ticketId);
            throw new SensiaException(ProcessState.SATURADO, "Servicio saturado: ya tiene " + MAX_POR_CLIENTE
                    + " documentos en proceso. Reintente más tarde.");
        }

        try {
            // La plaza del cliente se libera al terminar el procesamiento, no al recoger el resultado
            workers.submit(() -> {
                try {
                    ejecutarProcesamiento(ticketId, doc.fileContent(), context, recursos);
                } finally {
                    liberarPlaza(cliente);
                }
            });

        } catch (RejectedExecutionException e) {
            liberarPlaza(cliente);
            repositorioTrabajos.remove(ticketId);
            throw new SensiaException(ProcessState.SATURADO, "Servicio saturado: demasiados documentos en cola. Reintente más tarde.", e);
        }        

        return ticketId;
    }

    /** Ocupa una plaza del cliente si le queda alguna (comprobación y suma atómicas) */
    private boolean reservarPlaza(String cliente) {
        if (cliente == null) return true;
        boolean[] reservada = { false };
        activosPorCliente.compute(cliente, (k, n) -> {
            int actuales = n == null ? 0 : n;
            if (actuales >= MAX_POR_CLIENTE) return n;
            reservada[0] = true;
            return actuales + 1;
        });
        return reservada[0];
    }

    /** Devuelve la plaza; sin trabajos, el cliente sale del mapa */
    private void liberarPlaza(String cliente) {
        if (cliente == null) return;
        activosPorCliente.computeIfPresent(cliente, (k, n) -> n > 1 ? n - 1 : null);
    }


    public JobResultDTO consultarYBorrar(String ticketId) {

        JobInfo job = repositorioTrabajos.get(ticketId);

        if (job == null) {
            log.warn("Consulta de ticket inexistente: {}", ticketId);
            TaskStatusDTO taskStatus = new TaskStatusDTO(ProcessState.NOT_FOUND,
                    "El ticket solicitado no existe o ya fue procesado.");
            return new JobResultDTO(taskStatus, "", "", new byte[0], new ArrayList<RedactionItemDTO>());
        }

        try {
            if (job.getEstadoPublico().getProcessState() == ProcessState.COMPLETED) {
                return procesarDocumentoFinal(job);
            }
            else{
                return new JobResultDTO(job.getEstadoPublico(), job.getFileName(), job.getContentType(), new byte[0], job.getFindings());
            }
        } catch (Exception e) {
            log.error("Ticket {}: fallo al recuperar el resultado", ticketId, e);
            TaskStatusDTO taskStatus = new TaskStatusDTO(ProcessState.ERROR,
                    "No se ha podido recuperar el documento procesado (ref. " + ticketId.substring(0, 8) + ").");
            job.setEstadoPublico(taskStatus);
            return new JobResultDTO(taskStatus, job.getFileName(), job.getContentType(), new byte[0], job.getFindings());
        } finally {
             if (job.getEstadoPublico().getProcessState().esFinal()) {
                repositorioTrabajos.remove(ticketId);
                borrarFicherosFisicos(job);
                log.info("Ticket {} eliminado tras consulta final (Estado: {})", ticketId,
                        job.getEstadoPublico().getProcessState());
             }
        }

    }

    private JobResultDTO procesarDocumentoFinal(JobInfo job) throws IOException {
        if (job.getArchivoResultado() != null && job.getArchivoResultado().exists()) {
            long size = job.getArchivoResultado().length();
            if (size == 0) {
                throw new IOException("El fichero de resultado existe pero está vacío.");
            }

            byte[] fileBytes = Files.readAllBytes(job.getArchivoResultado().toPath());

            return new JobResultDTO(job.getEstadoPublico(), job.getFileName(), job.getContentType(), fileBytes,
                    job.getFindings());
        } else {
            throw new IOException("El archivo resultado no se encuentra en la ruta esperada.");
        }
    }

    /**
     * Lógica asíncrona de procesamiento
     */
    private void ejecutarProcesamiento(String ticketId, byte[] inputBytes, PdfProcessorContext context,
            CensorResources recursos) {

        JobInfo job = repositorioTrabajos.get(ticketId);
        if (job == null)
            return;

        MDC.put("ticketId", ticketId);
        job.setEstadoPublico(new TaskStatusDTO(ProcessState.PROCESSING, "Procesando."));
        File tempInput = null;
        File tempOutput = null;
        boolean exito = false;

        try {
            boolean esImagen = job.getContentType() != null && job.getContentType().startsWith("image/");
            validarFormato(inputBytes, esImagen);

            tempInput = File.createTempFile("job_in_" + ticketId, ".tmp");
            tempOutput = File.createTempFile("job_out_" + ticketId, ".tmp");
            Files.write(tempInput.toPath(), inputBytes);

            if (esImagen) {
                FaceDetector detector = FaceDetector.getFaceDetector();
                if (detector == null) {
                    throw new IllegalStateException("FaceDetectorYN no está inicializado");
                }
                try (RedactionResult detection = detector.detectFaces(
                        tempInput, context.yunetConfig(), context.faceConfig(), context.isPreview())) {
                    Files.write(tempOutput.toPath(), detection.getImageData());
                    job.setFindings(detection.getRedactionItems());
                    job.setContentType("image/jpeg");
                }

            } else if (job.getFindings() != null && !job.getFindings().isEmpty()) {
                validarHashes(job);
                log.info("Validación de hash correcta. Aplicando censuras revisadas por el usuario.");
                DocumentInfo docInfo = new DocumentInfo(tempInput, tempOutput, job.getDocumentHash());
                PdfCensor.aplicarCensuraDirecta(docInfo, context, job.getFindings());

            } else {
                DocumentInfo docInfo = new DocumentInfo(tempInput, tempOutput, job.getDocumentHash());
                job.setFindings(PdfCensor.aplicarCensura(docInfo, recursos, context));
            }

            job.setArchivoResultado(tempOutput);
            job.setEstadoPublico(new TaskStatusDTO(ProcessState.COMPLETED, "Procesamiento finalizado."));
            exito = true;

        } catch (Throwable t) {
            job.setEstadoPublico(traducirError(t, ticketId));
        } finally {
            if (tempInput != null) tempInput.delete();
            // Sin éxito, o purgado durante el proceso: nadie recogerá la salida
            if (tempOutput != null && (!exito || !repositorioTrabajos.containsKey(ticketId))) tempOutput.delete();
            MDC.remove("ticketId");
        }
    }

    private void validarHashes(JobInfo job) {
        for (RedactionItemDTO item : job.getFindings()) {
            if (item.apply() && !job.getDocumentHash().equals(item.sourceDocumentHash())) {
                throw new SensiaException(ProcessState.HASH_MISMATCH_ERROR,
                        "Las marcas confirmadas no corresponden a este documento.");
            }
        }
    }


    private TaskStatusDTO traducirError(Throwable t, String ticketId) {
        // Errores ya preparados para el usuario, aunque vengan envueltos (p. ej. "Error al sanitizar")
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SensiaException se) {
                log.warn("Ticket {} terminado con {}: {} (causa: {})",
                        ticketId, se.estado(), se.getMessage(), String.valueOf(se.getCause()));
                return new TaskStatusDTO(se.estado(), se.getMessage());
            }
            if (c instanceof InvalidPasswordException) {
                log.warn("Ticket {}: PDF protegido con contraseña", ticketId);
                return new TaskStatusDTO(ProcessState.INVALID_DOCUMENT_ERROR,
                        "El PDF está protegido con contraseña. Quite la protección y vuelva a enviarlo.");
            }
        }

        // Errores no previstos: detalle técnico al log, referencia al usuario
        String ref = ticketId.substring(0, 8);
        if (t instanceof OutOfMemoryError) {
            log.error("Ticket {}: memoria insuficiente", ticketId, t);
            return new TaskStatusDTO(ProcessState.ERROR,
                    "El documento es demasiado grande para procesarlo. Pruebe a dividirlo (ref. " + ref + ").");
        }
        log.error("Ticket {}: error no controlado", ticketId, t);
        return new TaskStatusDTO(ProcessState.ERROR,
                "Error interno al procesar el documento. Si se repite, comuníquelo al administrador "
                + "indicando la referencia " + ref + ".");
    }

    private static void validarFormato(byte[] b, boolean esImagen) {
        
        boolean valido = esImagen ? (esJpeg(b) || esPng(b)) : esPdf(b);
        if (!valido) {
            throw new SensiaException(ProcessState.INVALID_DOCUMENT_ERROR, esImagen
                    ? "El fichero no es una imagen JPEG o PNG válida."
                    : "El fichero no es un PDF válido o está dañado.");
        }
    }

    private static boolean esPdf(byte[] b) {
        // La especificación admite bytes previos a la cabecera %PDF- (hasta 1024)
        for (int i = 0; i <= Math.min(b.length - 5, 1024); i++) {
            if (b[i] == '%' && b[i + 1] == 'P' && b[i + 2] == 'D' && b[i + 3] == 'F' && b[i + 4] == '-') {
                return true;
            }
        }
        return false;
    }

    private static boolean esJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    private static boolean esPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /**
     * Tarea programada: revisa trabajos antiguos y los borra
     */
    private void limpiarTrabajosCaducados() {
        long ahora = System.currentTimeMillis();

        Iterator<Map.Entry<String, JobInfo>> it = repositorioTrabajos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JobInfo> entry = it.next();
            JobInfo info = entry.getValue();

            if (ahora - info.getTimestampCreacion() > TIEMPO_EXPIRACION_MS) {
                log.info("Purgando trabajo caducado: {}", entry.getKey());
                borrarFicherosFisicos(info);
                it.remove();
            }
        }
    }

    private void borrarFicherosFisicos(JobInfo job) {
        if (job.getArchivoResultado() != null && job.getArchivoResultado().exists()) {
            boolean borrado = job.getArchivoResultado().delete();
            if (!borrado) {
                log.warn("No se pudo borrar el archivo temporal: {}", job.getArchivoResultado().getAbsolutePath());
                job.getArchivoResultado().deleteOnExit();
            }
        }
    }

    public void shutdown() {
        log.info("Deteniendo AsyncJobManager...");
        scheduler.shutdownNow();
        workers.shutdownNow();
        repositorioTrabajos.values().forEach(this::borrarFicherosFisicos);
        repositorioTrabajos.clear();
    }
}
