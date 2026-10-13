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

package io.github.sensia.processor.img;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opencv.core.Rect;

import io.github.sensia.processor.ImageUtils;
import io.github.sensia.processor.model.RedactionResult;
import io.github.sensia.processor.model.config.FaceMaskingConfig;
import io.github.sensia.processor.model.config.YunetConfig;
import io.github.sensia.ws.ConfigManager;

/**
 * Instancia única del detector de rostros YuNet (OpenCV), compartida entre hilos.
 */
public class FaceDetector {
    private static final Logger log = LoggerFactory.getLogger(FaceDetector.class);
    // Margen alrededor del recuadro de YuNet, que puede dejar fuera pelo, orejas y barbilla
    private static final double MARGEN_ROSTRO = 0.20;
    // Celda mínima del mosaico: lado mayor de la imagen / CELDAS_IMAGEN (8 px en una imagen de 1024)
    private static final int CELDAS_IMAGEN = 128;

    static {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            log.info("Librería nativa de OpenCV cargada correctamente.");
        } catch (UnsatisfiedLinkError e) {
            log.error("ERROR: No se encuentra la librería de OpenCV.", e);
            System.exit(1);
        }
    }

    private FaceDetectorYN detector = null;

    private static volatile FaceDetector faceDetector;

    private static final int MAX_PIXELS = 1024;

    private static final String PATH_TO_YUNET_MODEL = "yunet/face_detection_yunet_2023mar_int8bq.onnx";

    /**
     * Segunda pasada de detección sobre una copia con el contraste local realzado (CLAHE).
     * Experimental: desactivada hasta validarla con imágenes propias (BACKLOG FEAT-007).
     * Se activa con processing.face.enhanceContrast=true o PROCESSING_FACE_ENHANCECONTRAST=true.
     */
    private static final boolean REALZAR_CONTRASTE = ConfigManager.getInstance("app.properties")
            .getBooleanConfig("processing.face.enhanceContrast", false);

    /** Solapamiento (IoU) a partir del cual dos detecciones se consideran el mismo rostro */
    private static final double IOU_MISMO_ROSTRO = 0.3;



    private FaceDetector(String pathToModel, float frameWidth, float frameHeight) {
        // Inicializar el detector YuNet
        detector = FaceDetectorYN.create(
                pathToModel,
                "",
                new Size(frameWidth, frameHeight));
    }

    public static FaceDetector getFaceDetector() {
        if (faceDetector == null) {
            synchronized (FaceDetector.class) {
                if (faceDetector == null) {
                    try {
                        File modelFile = saveResourceToFile(PATH_TO_YUNET_MODEL, "yunet",
                                ".onnx");
                        log.info("Inicializando el modelo de detección de caras...");
                        faceDetector = new FaceDetector(modelFile.getPath(), 640, 480);
                        log.info("Realce de contraste en la detección de rostros: {}",
                                REALZAR_CONTRASTE ? "activado" : "desactivado");
                        
                    } catch (Exception e) {
                        log.error("Error al inicializar el modelo.", e);
                        throw new RuntimeException("Error al inicializar el modelo.", e);
                    }
                }
            }
        }
        return faceDetector;
    }

    public synchronized void liberarRecursos() {
        // synchronized: espera a que termine la detección en curso.
        // FaceDetectorYN no tiene liberación explícita en Java, solo quitamos referencia
        log.info("Cerrando el detector de rostros.");
        detector = null;
    }

    private static File saveResourceToFile(String resourcePath, String prefix, String suffix) throws IOException {
        InputStream inputStream = FaceDetector.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null)
            throw new IOException("Resource not found: " + resourcePath);

        File tempFile = Files.createTempFile(prefix, suffix).toFile();
        tempFile.deleteOnExit();

        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } finally {
            inputStream.close();
        }
        return tempFile;
    }

    private static Mat scaleToFit(Mat sourceImage, int targetWidth, int targetHeight) {
        double scaleFactor = Math.min((double) targetWidth / sourceImage.width(),
                (double) targetHeight / sourceImage.height());
        Mat scaledImage = new Mat();
        Imgproc.resize(sourceImage, scaledImage,
                new Size(sourceImage.width() * scaleFactor, sourceImage.height() * scaleFactor), 0, 0,
                Imgproc.INTER_AREA);
        return scaledImage;
    }

    public synchronized RedactionResult detectFaces(File inputFile, YunetConfig yunetOpts, FaceMaskingConfig faceOpts,
            boolean redactionPreview)
            throws Exception {

        byte[] image = Files.readAllBytes(inputFile.toPath());
        return detectFaces(image, yunetOpts, faceOpts, redactionPreview);

    }

    /** Copia con el contraste local realzado (CLAHE sobre la luminancia). Solo para detectar. */
    private static Mat realzarContraste(Mat bgr) {
        Mat ycrcb = new Mat();
        List<Mat> canales = new ArrayList<>();
        Mat resultado = new Mat();
        try {
            Imgproc.cvtColor(bgr, ycrcb, Imgproc.COLOR_BGR2YCrCb);
            Core.split(ycrcb, canales);
            Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(canales.get(0), canales.get(0));
            Core.merge(canales, ycrcb);
            Imgproc.cvtColor(ycrcb, resultado, Imgproc.COLOR_YCrCb2BGR);
            return resultado;
        } finally {
            ycrcb.release();
            canales.forEach(Mat::release);
        }
    }

    /** Une las dos pasadas: de la segunda, solo los rostros que no solapan con uno de la primera */
    private static Mat unirDetecciones(Mat primera, Mat segunda) {
        if (segunda.rows() == 0) return primera.clone();
        if (primera.rows() == 0) return segunda.clone();
        List<Mat> filas = new ArrayList<>();
        for (int i = 0; i < primera.rows(); i++) filas.add(primera.row(i));
        for (int j = 0; j < segunda.rows(); j++) {
            boolean nueva = true;
            for (int i = 0; i < primera.rows() && nueva; i++) {
                nueva = iou(primera, i, segunda, j) < IOU_MISMO_ROSTRO;
            }
            if (nueva) filas.add(segunda.row(j));
        }
        Mat unidas = new Mat();
        Core.vconcat(filas, unidas);
        return unidas;
    }

    /** Intersección sobre unión de los recuadros (x, y, w, h) de dos detecciones */
    private static double iou(Mat a, int i, Mat b, int j) {
        float[] r = new float[4];
        float[] s = new float[4];
        a.get(i, 0, r);
        b.get(j, 0, s);
        double ix = Math.max(0, Math.min(r[0] + r[2], s[0] + s[2]) - Math.max(r[0], s[0]));
        double iy = Math.max(0, Math.min(r[1] + r[3], s[1] + s[3]) - Math.max(r[1], s[1]));
        double inter = ix * iy;
        double union = (double) r[2] * r[3] + (double) s[2] * s[3] - inter;
        return union > 0 ? inter / union : 0;
    }

    /**
     * Oculta el rostro con un mosaico proporcional a su tamaño
     */
    private static void ocultarRostro(Mat frame, int x, int y, int width, int height, int intensidad) {
        int mx = (int) Math.round(width * MARGEN_ROSTRO);
        int my = (int) Math.round(height * MARGEN_ROSTRO);
        int x0 = Math.max(0, x - mx);
        int y0 = Math.max(0, y - my);
        int x1 = Math.min(frame.cols(), x + width + mx);
        int y1 = Math.min(frame.rows(), y + height + my);
        if (x1 <= x0 || y1 <= y0)
            return;

        Rect zona = new Rect(x0, y0, x1 - x0, y1 - y0);
        Mat roi = frame.submat(zona);
        Mat reducida = new Mat();
        Mat ampliada = new Mat();
        try {       
            int nivel = Math.max(0, Math.min(100, intensidad));
            int bloques = Math.max(4, (int) Math.round(16 - nivel * 0.12));
            int ladoMayor = Math.max(zona.width, zona.height);
            // Celda mínima proporcional a la imagen: en los rostros pequeños, menos celdas y más grandes,
            // para que el mosaico no deje el rostro reconocible
            int celdaMinima = Math.max(6, Math.max(frame.cols(), frame.rows()) / CELDAS_IMAGEN);
            int celdas = Math.min(bloques, ladoMayor / celdaMinima);
            // Con 100, o si no caben dos celdas, se rellena de color
            if (nivel >= 100 || celdas < 2) {
                roi.setTo(Core.mean(roi));
                return;
            }
            Imgproc.resize(roi, reducida,
                    new Size(Math.max(1, Math.round((double) zona.width * celdas / ladoMayor)),
                            Math.max(1, Math.round((double) zona.height * celdas / ladoMayor))),
                    0, 0, Imgproc.INTER_AREA);
            Imgproc.resize(reducida, ampliada, zona.size(), 0, 0, Imgproc.INTER_NEAREST);
            ampliada.copyTo(roi);
        } finally {
            reducida.release();
            ampliada.release();
            roi.release();
        }
    }


    public synchronized RedactionResult detectFaces(byte[] image, YunetConfig yunetOpts, FaceMaskingConfig faceOpts,
            boolean redactionPreview)
            throws Exception {

        if (detector == null) {
            throw new IllegalStateException("El detector de rostros no está inicializado o ha sido cerrado.");
        }

        // Sin maskFaces no se buscan rostros: la imagen se devuelve sin detecciones (manual, 5.5)
        boolean buscarRostros = faceOpts.maskFaces();

        float scoreThreshold = yunetOpts.scoreThreshold();
        float nmsThreshold = yunetOpts.nmsThreshold();
        int topK = yunetOpts.topK();
        float minRatio = faceOpts.minFaceAspectRatio();
        float maxRatio = faceOpts.maxFaceAspectRatio();
        float minArea = faceOpts.minFaceArea();
        float maxArea = faceOpts.maxFaceArea();
        int pixelationMask = faceOpts.pixelationIntensity();

        Mat frame;
        try {
            frame = ImageUtils.convertBinaryToMat(image);
        } catch (Exception e) {
            log.error("Error al decodificar la imagen.", e);
            throw e;
        }
        
        Mat faces = new Mat();
        Mat facesSel = null;
        Mat facesFinal = null;
        // Copia reducida a MAX_PIXELS solo para detectar. 
        // Los rostros se ocultan sobre la imagen original (frame), que es la que se devuelve
        Mat entrada = frame;

        boolean exito = false;
        // Tamaño analizado / tamaño original
        double escala = 1.0;

        try {
            if (frame.width() > MAX_PIXELS || frame.height() > MAX_PIXELS) {
                entrada = scaleToFit(frame, MAX_PIXELS, MAX_PIXELS);
                escala = (double) entrada.width() / frame.width();
            }

            if (buscarRostros) {
                detector.setScoreThreshold(scoreThreshold);
                detector.setNMSThreshold(nmsThreshold);
                detector.setTopK(topK);
                detector.setInputSize(new Size(entrada.width(), entrada.height()));
                detector.detect(entrada, faces);                
            }

            if (buscarRostros && REALZAR_CONTRASTE) {
                Mat realzada = realzarContraste(entrada);
                Mat segunda = new Mat();
                try {
                    detector.detect(realzada, segunda);
                    Mat unidas = unirDetecciones(faces, segunda);
                    faces.release();
                    faces = unidas;
                } finally {
                    realzada.release();
                    segunda.release();
                }
            }
            

            facesSel = new Mat(faces.rows(), faces.cols(), faces.type(), new Scalar(0));
            int contadorSel = 0;
            // Grosor de las marcas de la vista previa, proporcional a la imagen original
            int grosor = Math.max(2, (int) Math.round(2 / escala));            

            if (faces.rows() > 0) {
                for (int i = 0; i < faces.rows(); i++) {
                    float[] data = new float[faces.cols()];
                    faces.get(i, 0, data);

                    int x = (int) data[0], y = (int) data[1], width = (int) data[2], height = (int) data[3];

                    
                    if (width <= 0 || height <= 0)
                        continue;

                    // Filtros del manual (5.5): área en píxeles de la imagen original y proporción
                    // ancho/alto. Cada límite es independiente; 0 lo desactiva.
                    double areaCara = (double) width * height / (escala * escala);
                    if (minArea > 0 && areaCara < minArea)
                        continue;
                    if (maxArea > 0 && areaCara > maxArea)
                        continue;
                    double ratio = (double) width / height;
                    if (minRatio > 0 && ratio < minRatio)
                        continue;
                    if (maxRatio > 0 && ratio > maxRatio)
                        continue;

                    // El recuadro y puntos de referencia en píxeles de la imagen original (la última columna es la confianza)
                    for (int k = 0; k < Math.min(14, data.length); k++) {
                        data[k] = (float) (data[k] / escala);
                    }
                    facesSel.put(contadorSel++, 0, data);
                    x = (int) Math.floor(data[0]);
                    y = (int) Math.floor(data[1]);
                    width = (int) Math.ceil(data[2]);
                    height = (int) Math.ceil(data[3]);

                    if (redactionPreview) {
                        Imgproc.rectangle(frame, new Rect(x, y, width, height), new Scalar(0, 255, 0), grosor);
                        Imgproc.circle(frame, new Point(data[4], data[5]), grosor, new Scalar(255, 0, 0), grosor);
                        Imgproc.circle(frame, new Point(data[6], data[7]), grosor, new Scalar(0, 0, 255), grosor);                        
                    } else {
                        ocultarRostro(frame, x, y, width, height, pixelationMask);
                    }
                }
            }

            facesFinal = facesSel.rowRange(0, contadorSel).clone();

            String documentHash = ImageUtils.calcularSHA256(image);

            RedactionResult resultado = new RedactionResult(facesFinal, frame, documentHash);

            exito = true;

            return resultado;

        } finally {
            if (entrada != frame)
                entrada.release();
            if (faces != null)
                faces.release();
            if (facesSel != null)
                facesSel.release();

            // NOTA. Libera los recursos también si hay una excepción
            if (!exito) {
                log.warn("Excepción durante detectFaces. Forzando limpieza de memoria nativa...");
                if (frame != null)
                    frame.release();
                if (facesFinal != null)
                    facesFinal.release();
            }
        }
    }

}