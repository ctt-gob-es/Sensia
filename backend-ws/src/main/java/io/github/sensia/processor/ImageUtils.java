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

package io.github.sensia.processor;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sensia.ws.generated.DocumentItem;
import io.github.sensia.ws.generated.DocumentList;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ImageUtils {

    private static final Logger logger = LoggerFactory.getLogger(ImageUtils.class);
    // Calidad al recodificar en JPEG para buscar rostros: visualmente equivalente a la original
    private static final float CALIDAD_JPEG = 0.95f;
   
    static public byte[] extractImageBytes(PDImageXObject pdImage) throws Exception {
        if (pdImage == null) {
            return new byte[0];
        }

        try {
            java.awt.image.BufferedImage bImage = pdImage.getImage();
            if (bImage == null) {
                return new byte[0];
            }

            String format = pdImage.getSuffix();
            if (format == null || format.trim().isEmpty() ||
                    format.equalsIgnoreCase("jpx") || format.equalsIgnoreCase("jb2")
                    || format.equalsIgnoreCase("tiff")) {
                format = "png";
            } else {
                format = format.toLowerCase();
            }

            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                boolean success = false;
                try {
                    success = "jpg".equals(format)
                            ? escribirJpeg(bImage, baos, CALIDAD_JPEG)
                            : javax.imageio.ImageIO.write(bImage, format, baos);
                } catch (Exception e) {
                    logger.warn("Excepción al intentar escribir el formato de imagen '{}'. Forzando el uso de png.",
                            format);
                }

                if (!success) {
                    baos.reset();
                    javax.imageio.ImageIO.write(bImage, "png", baos);
                }

                return baos.toByteArray();
            }

        } catch (Exception e) {
            logger.error("Error extrayendo los bytes de la imagen desde PDImageXObject", e);
            throw e;
        }
    }

    //
    // JPEG con calidad explícita (ImageIO.write usa 0,75). Devuelve false si no hay codificador.
    //
    private static boolean escribirJpeg(BufferedImage imagen, java.io.OutputStream salida, float calidad)
            throws IOException {
        java.util.Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return false;
        }
        javax.imageio.ImageWriter writer = writers.next();
        try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(salida)) {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(calidad);
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(imagen, null, null), param);
            return true;
        } finally {
            writer.dispose();
        }
    }

    public static BufferedImage bytesToBufferedImage(byte[] imageBytes) throws IOException {

        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("El array de bytes recibido está vacío o es nulo.");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
            BufferedImage bImage = ImageIO.read(bais);
            
            if (bImage == null) {
                throw new IOException("ImageIO no pudo decodificar los bytes a BufferedImage (formato no soportado o datos corruptos).");
            }
            
            return bImage;
        }
    }


    public static DocumentList extractImages(String fileName, String contentType, byte[] pdfBytes, double minH,
            double minW, double maxH, double maxW) throws IOException {

        int imageCounter = 0;

        DocumentList docs = new DocumentList();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {

            for (PDPage page : document.getPages()) {
                PDResources resources = page.getResources();

                for (COSName cosName : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(cosName);

                    if (xObject instanceof PDImageXObject) {
                        PDImageXObject pDImage = (PDImageXObject) xObject;

                        BufferedImage bImage = pDImage.getImage();
                        String format = "png";
                        if (bImage.getHeight() < minH || bImage.getWidth() < minW
                                || bImage.getHeight() > maxH || bImage.getWidth() > maxW) {
                            logger.info("Imagen descartada por tamaño: alto {} ancho {}", bImage.getHeight(), bImage.getWidth());
                            continue;
                        }
                        
                        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                            ImageIO.write(bImage, format, baos);

                            byte[] imageBytes = baos.toByteArray();

                            logger.info(
                                    "Imagen {}: Formato {}, alto: {} ancho {} Tamaño: {} bytes. pass",
                                    imageCounter, format, bImage.getHeight(), bImage.getWidth(), imageBytes.length);

                            String imageName = String.format("image-%d-%s", imageCounter, fileName);
                            DocumentItem image = new DocumentItem();
                            image.setFileName(imageName);
                            image.setContentType("image/png");
                            image.setFileContent(imageBytes);
                            docs.getDocument().add(image);

                        } catch (IOException e) {
                            logger.error("Error al convertir la imagen a bytes: " + e.getMessage());
                            throw e;
                        }

                    }
                }
            }
            return docs;
        }
    }

    
    public static Mat convertBinaryToMat(byte[] imageData) {
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("Error en convertBinaryToMat: array de bytes vacío.");
        }

        MatOfByte matOfByte = new MatOfByte(imageData);
        try {
            Mat matImage = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);
            if (matImage != null && !matImage.empty()) {
                return matImage;
            }
            
            logger.warn("OpenCV devolvió vacío. Usando ImageIO...");

        } catch (Exception e) {
            logger.warn("OpenCV rechazó el formato. usamos fallback con ImageIO: {}", e.getMessage());
        } finally {
            if (matOfByte != null) matOfByte.release();
        }

        try {
            BufferedImage bImage = ImageUtils.bytesToBufferedImage(imageData);

            BufferedImage convertedImage = new BufferedImage(
                    bImage.getWidth(), bImage.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            convertedImage.getGraphics().drawImage(bImage, 0, 0, null);

            byte[] pixels = ((java.awt.image.DataBufferByte) convertedImage.getRaster().getDataBuffer()).getData();
            Mat mat = new Mat(convertedImage.getHeight(), convertedImage.getWidth(), CvType.CV_8UC3);
            mat.put(0, 0, pixels);

            return mat;

        } catch (Exception ex) {
            throw new RuntimeException("Error imagen corrupta. ni OpenCV ni ImageIO han podido decodificarla.", ex);
        }
    }

    public static String calcularSHA256(byte[] documentoBytes) {
        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            byte[] hashCalculado = digest.digest(documentoBytes);
            
            return bytesToHex(hashCalculado);
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("El algoritmo SHA-256 no está disponible en el entorno", e);
        }
    }


    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

}
