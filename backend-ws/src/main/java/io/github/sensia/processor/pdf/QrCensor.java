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

package io.github.sensia.processor.pdf;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.multi.MultipleBarcodeReader;

import io.github.sensia.processor.model.DetectionType;
import io.github.sensia.processor.model.config.DocumentInfo;
import io.github.sensia.processor.model.dto.ActionStateDTO;
import io.github.sensia.processor.model.dto.CoordinateDTO;
import io.github.sensia.processor.model.dto.RedactionItemDTO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;

public class QrCensor {

    private static final Logger logger = LoggerFactory.getLogger(QrCensor.class);
    private static final Logger logSensible = LoggerFactory.getLogger("sensia.sensible.pdf");

    private static final int MAX_CODES1PAG = 6;
    private static final int MAX_CODES = 4;

    public static List<RedactionItemDTO> detectarQRsEnPagina(PDDocument document, int pageIndex,
            List<RedactionItemDTO> cacheQRs, DocumentInfo docInfo)
            throws IOException {

        logger.info("Buscando QRs...");

        List<RedactionItemDTO> qrsEncontrados = new ArrayList<>();

        int currentPageNum = pageIndex + 1;
        boolean esPrimeraPagina = (pageIndex == 0);
        boolean esSegundaPagina = (pageIndex == 1);
        // boolean esUltimaPagina = (pageIndex == document.getNumberOfPages() - 1);
        boolean forzarEscaneoCompleto = esPrimeraPagina; // || esUltimaPagina;

        PDPage page = document.getPage(pageIndex);
        float dpiScan = calcularDpiOptimo(page);
        logger.debug("QR/codes. DPI óptimo calculado: {}", dpiScan);

        if (!forzarEscaneoCompleto && cacheQRs != null && !cacheQRs.isEmpty()) {
            boolean exitoClonando = false;
            for (RedactionItemDTO plantilla : cacheQRs) {
                if (verificarTintaEnZona(document, pageIndex, plantilla.coordinates(), dpiScan)) {
                    qrsEncontrados.add(new RedactionItemDTO(
                            DetectionType.QR_CODE,
                            "",
                            ActionStateDTO.REDACTED,
                            "Código QR/Barras censurado (Clonado)",
                            plantilla.text(),
                            currentPageNum,
                            plantilla.coordinates(), 
                            docInfo.documentHash(),
                            true,
                            ""));
                    exitoClonando = true;
                }
            }

            if (exitoClonando) {
                logger.info("   Pág {}: Códigos de margen clonados. Se escanea el resto de la página.", currentPageNum);
            }
        }

        List<BarcodeFormat> formatos = new ArrayList<>();
        formatos.add(BarcodeFormat.QR_CODE);
        formatos.add(BarcodeFormat.PDF_417);
        formatos.add(BarcodeFormat.DATA_MATRIX);
        formatos.add(BarcodeFormat.CODE_128);
        formatos.add(BarcodeFormat.CODE_39);
        formatos.add(BarcodeFormat.EAN_13);
        formatos.add(BarcodeFormat.ITF);

        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hintsFast = new HashMap<>();
        hintsFast.put(DecodeHintType.POSSIBLE_FORMATS, formatos);
        Map<DecodeHintType, Object> hintsHard = new HashMap<>();
        hintsHard.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hintsHard.put(DecodeHintType.POSSIBLE_FORMATS, formatos);

        MultipleBarcodeReader multiReader = new GenericMultipleBarcodeReader(reader);

        PDFRenderer renderer = new PDFRenderer(document);

        PDRectangle cropBox = page.getCropBox();

        float pageWidth = cropBox.getWidth();
        float pageHeight = cropBox.getHeight();

        BufferedImage fullImage = renderer.renderImageWithDPI(pageIndex, dpiScan, ImageType.GRAY);
        // Copia de trabajo: cada código encontrado se tapa aquí para que no oculte a sus repetidos.
        // fullImage queda intacta para calcular las cajas finales.
        BufferedImage trabajo = new BufferedImage(fullImage.getColorModel(), fullImage.copyData(null),
                fullImage.isAlphaPremultiplied(), null);

        List<Result> allResults = new ArrayList<>();
        int w = fullImage.getWidth();
        int h = fullImage.getHeight();
        int maxCodes = (esPrimeraPagina ? MAX_CODES1PAG : MAX_CODES);

        // Intento 1: página completa (si el documento está limpio, los detecta)
        escanearImagen(trabajo, multiReader, hintsFast, allResults, 0, 0, dpiScan);
        eliminarResultadosDuplicados(allResults);

        // Intento 2: Franjas Horizontales --> Caza los códigos horizontales largos, ignora si ya tenemos los maxCodes
        if (allResults.size() < maxCodes && h > 200) {
            logger.info("QR/codes. Escaneando franjas horizontales... {} de {}", allResults.size(), maxCodes);
            int altoFranja = h / 6;
            int solapamientoY = altoFranja / 2;
            int anchoUtil = (int) (w * 0.90);
            
            for (int i = 0; i < 6; i++) {

                int y = Math.max(0, (i * altoFranja) - (i > 0 ? solapamientoY : 0));
                int alturaActual = Math.min(altoFranja + solapamientoY, h - y);

                BufferedImage franjaH = trabajo.getSubimage(0, y, anchoUtil, alturaActual);
                escanearImagen(franjaH, multiReader, hintsHard, allResults, 0, y, dpiScan);

                // DEBUG: volcar a disco para inspección visual
                //String fileName = String.format("page_%04d_franja_%08d", pageIndex , y );
                //try {
                //    javax.imageio.ImageIO.write(franjaH, "png", new java.io.File("debug_franja_y" + fileName + "_original.png"));
                //} catch (IOException ex) {
                //    logger.warn("No se pudo volcar imagen debug", ex);
                //}

                logger.debug("QR/codes. franja={} codes={} de {}", y, allResults.size(), maxCodes);
            }
        }
          debugResultadosQR("ANTES dedup tras pantalla completa + franjas horizontales", pageIndex, allResults);

        eliminarResultadosDuplicados(allResults);

          debugResultadosQR("DESPUÉS dedup tras pantalla completa + franjas horizontales", pageIndex, allResults);

        // Intento 3: Franjas Verticales --> Caza los códigos verticales de margen, ignora si ya tenemos los maxCodes
        if (allResults.size() < maxCodes && w > 200) {
            logger.info("QR/codes. Escaneando franjas verticales... {} de {}", allResults.size(), maxCodes);
            int anchoFranja = w / 6;
            int solapamientoX = anchoFranja / 2;

            for (int i = 0; i < 6; i++) {
                if (allResults.size() >= maxCodes) break;
                int x = Math.max(0, (i * anchoFranja) - (i > 0 ? solapamientoX : 0));
                int anchoActual = Math.min(anchoFranja + solapamientoX, w - x);

                BufferedImage franjaV = trabajo.getSubimage(x, 0, anchoActual, h);
                escanearImagen(franjaV, multiReader, hintsHard, allResults, x, 0, dpiScan);
            }
        }

        debugResultadosQR("ANTES dedup tras escaneo horizonal completa + franjas verticales", pageIndex, allResults);

        eliminarResultadosDuplicados(allResults);

        debugResultadosQR("DESPUÉS dedup  tras escaneo horizonal + franjas verticales", pageIndex, allResults);



        // Zona inferior específica
        if (esPrimeraPagina && allResults.size() < maxCodes) {
            logger.info("QR/codes. Escaneando zona inferior específica...");

            int xInf = (int) (w * 0.30);
            int yInf = (int) (h * 0.78);

            int anchoInf = (int) (w * 0.60);
            int altoInf = h - yInf;

            if (xInf + anchoInf > w) {
                anchoInf = w - xInf;
            }

            if (altoInf > 100 && anchoInf > 100) {
                BufferedImage zonaInferior = trabajo.getSubimage(
                        xInf,
                        yInf,
                        anchoInf,
                        altoInf
                );

                escanearImagen(
                        zonaInferior,
                        multiReader,
                        hintsHard,
                        allResults,
                        xInf,
                        yInf,
                        dpiScan
                );
            }
        }

        debugResultadosQR("ANTES dedup tras horizontales + zona inferior", pageIndex, allResults);

        eliminarResultadosDuplicados(allResults);

        debugResultadosQR("DESPUÉS dedup tras horizontales + zona inferior", pageIndex, allResults);


        // Pasada extra sin bordes de tabla (README, «Limitaciones conocidas»)
        if (allResults.size() < maxCodes) {
            BufferedImage limpia = detectarSinLineasLargas(trabajo, dpiScan);
            escanearImagen(limpia, multiReader, hintsHard, allResults, 0, 0, dpiScan);
            eliminarResultadosDuplicados(allResults);
            limpia.flush();
        }
        
        if (!allResults.isEmpty()) {
            for (Result result : allResults) {

                ResultPoint[] points = result.getResultPoints();
                if (points == null || points.length == 0)
                    continue;

                CoordinateDTO coord = calcularCoordenadasReales(points, fullImage, page, cropBox, dpiScan);
                RedactionItemDTO ritem = new RedactionItemDTO(
                        DetectionType.QR_CODE,
                        "",
                        ActionStateDTO.REDACTED,
                        "Código QR censurado",
                        result.getText(),
                        currentPageNum,
                        coord,
                        docInfo.documentHash(),
                        true,
                    "");

                qrsEncontrados.add(ritem);

                if (esSegundaPagina && cacheQRs != null && esZonaMargen(coord, pageWidth, pageHeight)) {
                    cacheQRs.add(ritem);
                    logger.info("   Pág {}: Código en margen detectado. Guardado en caché para clonarlo.",
                            currentPageNum);
                }
            }

            logger.info("   Pág {}: Detectados {} códigos.", (currentPageNum), allResults.size());
        }

        fullImage.flush();
        trabajo.flush();
        return qrsEncontrados;

    }

    

    // Copia sin líneas rectas largas (bordes de tabla). Los lectores 1D exigen una zona en blanco
    // antes del patrón de inicio y un borde de celda pegado al código hace que se descarte.
    // Solo se usa en una pasada extra: las pasadas normales ya trabajan sobre la imagen sin borrar.
    private static BufferedImage detectarSinLineasLargas(BufferedImage src, float dpi) {
        BufferedImage img = new BufferedImage(src.getColorModel(), src.copyData(null),
                src.isAlphaPremultiplied(), null);
        java.awt.image.Raster in = src.getRaster();
        java.awt.image.WritableRaster out = img.getRaster();
        int w = img.getWidth(), h = img.getHeight();
        int largoMin = (int) dpi;   // ~25 mm: más que la altura de las barras de un código 1D
        int umbral = 230;           // incluye el gris del suavizado, que el binarizador vería como línea

        // Verticales: se borra la columna y sus vecinas para no dejar restos del suavizado
        for (int x = 0; x < w; x++) {
            int inicio = -1;
            for (int y = 0; y <= h; y++) {
                boolean oscuro = y < h && in.getSample(x, y, 0) < umbral;
                if (oscuro && inicio < 0) {
                    inicio = y;
                } else if (!oscuro && inicio >= 0) {
                    if (y - inicio >= largoMin) {
                        for (int k = inicio; k < y; k++)
                            for (int dx = Math.max(0, x - 1); dx <= Math.min(w - 1, x + 1); dx++)
                                out.setSample(dx, k, 0, 255);
                    }
                    inicio = -1;
                }
            }
        }
        // Horizontales: igual, por filas
        for (int y = 0; y < h; y++) {
            int inicio = -1;
            for (int x = 0; x <= w; x++) {
                boolean oscuro = x < w && in.getSample(x, y, 0) < umbral;
                if (oscuro && inicio < 0) {
                    inicio = x;
                } else if (!oscuro && inicio >= 0) {
                    if (x - inicio >= largoMin) {
                        for (int k = inicio; k < x; k++)
                            for (int dy = Math.max(0, y - 1); dy <= Math.min(h - 1, y + 1); dy++)
                                out.setSample(k, dy, 0, 255);
                    }
                    inicio = -1;
                }
            }
        }
        return img;
    }


    private static void debugResultadosQR(String fase, int pageIndex, List<Result> resultados) {
        if (!logger.isDebugEnabled()) {
            return;
        }

        int pageNum = pageIndex + 1;

        logger.debug("====== QR DEBUG Pág {} | {} | total={} ======",
                pageNum, fase, resultados.size());

        int i = 1;

        for (Result r : resultados) {
            ResultPoint[] pts = r.getResultPoints();

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float maxY = Float.MIN_VALUE;

            StringBuilder puntos = new StringBuilder();

            if (pts != null) {
                for (ResultPoint p : pts) {
                    if (p == null) {
                        continue;
                    }

                    minX = Math.min(minX, p.getX());
                    minY = Math.min(minY, p.getY());
                    maxX = Math.max(maxX, p.getX());
                    maxY = Math.max(maxY, p.getY());

                    puntos.append(String.format("(%.1f, %.1f) ", p.getX(), p.getY()));
                }
            }

            String bbox;
            if (pts == null || pts.length == 0 || minX == Float.MAX_VALUE) {
                bbox = "sin puntos";
            } else {
                bbox = String.format(
                        "x=%.1f y=%.1f w=%.1f h=%.1f",
                        minX,
                        minY,
                        maxX - minX,
                        maxY - minY
                );
            }

            logSensible.debug(
                "QR DEBUG Pág {} #{} format={} text='{}' bboxPx=[{}] points={}",
                pageNum,
                i++,
                r.getBarcodeFormat(),
                r.getText(),
                bbox,
                puntos
            );
        }

        logger.debug("====== FIN QR DEBUG Pág {} | {} ======", pageNum, fase);
    }

    private static void escanearImagen(BufferedImage image, MultipleBarcodeReader reader,
            Map<DecodeHintType, Object> hints, List<Result> accumulator,
            int offsetX, int offsetY, float dpi) {
        // image es una vista de la copia de trabajo: lo que se tapa aquí se ve en las pasadas siguientes.
        // Se repite porque ZXing descarta en una misma lectura los códigos con texto repetido.
        for (int intento = 0; intento < 5; intento++) {
            Result[] results;
            try {
                // La fuente copia los píxeles al crearse: hay que crearla de nuevo tras tapar
                LuminanceSource source = new BufferedImageLuminanceSource(image);
                results = reader.decodeMultiple(new BinaryBitmap(new HybridBinarizer(source)), hints);
            } catch (NotFoundException e) {
                return;
            }
            if (results == null || results.length == 0) return;

            List<Result> nuevos = new ArrayList<>();
            agregarResultados(results, nuevos, 0, 0);   // filtra nulls; coordenadas de esta imagen
            if (nuevos.isEmpty()) return;

            tapar(image, nuevos, dpi);
            agregarResultados(nuevos.toArray(new Result[0]), accumulator, offsetX, offsetY);
        }
    }

    // Tapa en blanco (color de fondo) los códigos encontrados
    private static void tapar(BufferedImage image, List<Result> resultados, float dpi) {
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            for (Result r : resultados) {
                int[] c = encontrarBordesReales(image, r.getResultPoints(), dpi);
                g.fillRect(c[0] - 5, c[1] - 5, c[2] + 10, c[3] + 10);
            }
        } finally {
            g.dispose();
        }
    }

    private static void agregarResultados(Result[] results, List<Result> accumulator, int offX, int offY) {
        for (Result r : results) {
            ResultPoint[] originales = r.getResultPoints();
            if (originales == null) continue;
            ResultPoint[] puntos = Arrays.stream(originales)
                    .filter(Objects::nonNull)
                    .map(p -> new ResultPoint(p.getX() + offX, p.getY() + offY))
                    .toArray(ResultPoint[]::new);
            if (puntos.length == 0) continue;
            accumulator.add(new Result(r.getText(), r.getRawBytes(), puntos, r.getBarcodeFormat()));
        }
    }

    private static CoordinateDTO calcularCoordenadasReales(ResultPoint[] points, BufferedImage fullImage, 
                                                           PDPage page, PDRectangle cropBox, float dpiScan) {

        int rot = ((page.getRotation() % 360) + 360) % 360;
        float W = cropBox.getWidth(), H = cropBox.getHeight();
        boolean girada = rot == 90 || rot == 270;
        // Puntos PDF por píxel (la imagen está girada tal como se ve en pantalla)
        float fx = (girada ? H : W) / fullImage.getWidth();
        float fy = (girada ? W : H) / fullImage.getHeight();

        int[] c = encontrarBordesReales(fullImage, points, dpiScan);
        float x0 = c[0] * fx, x1 = (c[0] + c[2]) * fx;   // pantalla: X hacia la derecha
        float y0 = c[1] * fy, y1 = (c[1] + c[3]) * fy;   // pantalla: Y hacia abajo

        // De pantalla a espacio de usuario sin rotar, el mismo que usan las cajas de texto
        float ux0, ux1, uy0, uy1;
        switch (rot) {
            case 90  -> { ux0 = y0;     ux1 = y1;     uy0 = x0;     uy1 = x1; }
            case 180 -> { ux0 = W - x1; ux1 = W - x0; uy0 = y0;     uy1 = y1; }
            case 270 -> { ux0 = W - y1; ux1 = W - y0; uy0 = H - x1; uy1 = H - x0; }
            default  -> { ux0 = x0;     ux1 = x1;     uy0 = H - y1; uy1 = H - y0; }
        }

        float padding = 3.0f;
        return new CoordinateDTO(
                cropBox.getLowerLeftX() + ux0 - padding,
                cropBox.getLowerLeftY() + uy0 - padding,
                (ux1 - ux0) + 2 * padding,
                (uy1 - uy0) + 2 * padding);
    }

    private static int[] encontrarBordesReales(BufferedImage image, ResultPoint[] points, float dpi) {
        int width = image.getWidth();
        int height = image.getHeight();
        int pMinX = width, pMaxX = 0, pMinY = height, pMaxY = 0;
        for (ResultPoint p : points) {
            int px = (int) p.getX();
            int py = (int) p.getY();
            if (px < pMinX) pMinX = px;
            if (px > pMaxX) pMaxX = px;
            if (py < pMinY) pMinY = py;
            if (py > pMaxY) pMaxY = py;
        }

        int cx = (pMinX + pMaxX) / 2;
        int cy = (pMinY + pMaxY) / 2;

        boolean is1D = points.length == 2;
        if (is1D) {
            boolean isHorizontal = (pMaxX - pMinX) > (pMaxY - pMinY);
            if (isHorizontal) {
                return expandirCodigoHorizontal(image, pMinX, pMaxX, cy, dpi);
            } else {
                return expandirCodigoVertical(image, pMinY, pMaxY, cx, dpi);
            }
        } else {
            int[] caja = expandirQR(image, cx, cy, dpi);
            int mx = (int) ((pMaxX - pMinX) * 0.25f), my = (int) ((pMaxY - pMinY) * 0.25f);
            int x0 = Math.max(0, Math.min(caja[0], pMinX - mx));
            int y0 = Math.max(0, Math.min(caja[1], pMinY - my));
            int x1 = Math.min(width - 1, Math.max(caja[0] + caja[2], pMaxX + mx));
            int y1 = Math.min(height - 1, Math.max(caja[1] + caja[3], pMaxY + my));
            return new int[]{x0, y0, x1 - x0, y1 - y0};
        }
    }

    // Una fila es del código si coincide (claro/oscuro) en al menos el 75 % de sus píxeles con la de referencia
    private static boolean filaIgual(BufferedImage img, int y, int yRef, int x0, int x1, int umbral) {
        int iguales = 0;
        for (int x = x0; x <= x1; x++) {
            if (esPixelOscuro(img.getRGB(x, y), umbral) == esPixelOscuro(img.getRGB(x, yRef), umbral)) iguales++;
        }
        return iguales >= (x1 - x0 + 1) * 0.75f;
    }

    private static int[] expandirCodigoHorizontal(BufferedImage image, int startX, int endX, int centerY, float dpi) {
        int width = image.getWidth();
        int height = image.getHeight();
        int umbralGris = (dpi <= 150) ? 150 : 170;
        int toleranciaEstricta = 5;
        int zonaSilencio = Math.max(8, (int) (dpi / 20));   // ~1,3 mm en blanco = fin del código

        // A lo ancho: ZXing da el centro de los patrones de inicio y fin, no los bordes
        int minX = startX, blancos = 0;
        while (minX > 0 && blancos < zonaSilencio) {
            minX--;
            blancos = esPixelOscuro(image.getRGB(minX, centerY), umbralGris) ? 0 : blancos + 1;
        }
        minX += blancos;
        int maxX = endX;
        blancos = 0;
        while (maxX < width - 1 && blancos < zonaSilencio) {
            maxX++;
            blancos = esPixelOscuro(image.getRGB(maxX, centerY), umbralGris) ? 0 : blancos + 1;
        }
        maxX -= blancos;

        // En altura: cada fila del código repite la última aceptada
        int minY = centerY;
        blancos = 0;
        for (int y = centerY - 1; y >= 0 && blancos < toleranciaEstricta; y--) {
            if (filaIgual(image, y, minY, minX, maxX, umbralGris)) { minY = y; blancos = 0; } else blancos++;
        }
        int maxY = centerY;
        blancos = 0;
        for (int y = centerY + 1; y < height && blancos < toleranciaEstricta; y++) {
            if (filaIgual(image, y, maxY, minX, maxX, umbralGris)) { maxY = y; blancos = 0; } else blancos++;
        }

        int finalMinX = Math.max(0, minX - 5);
        int finalMaxX = Math.min(width - 1, maxX + 5);
        return new int[]{finalMinX, minY, finalMaxX - finalMinX, maxY - minY};
    }

    
    // Una columna es del código si coincide (claro/oscuro) en al menos el 75 % de sus píxeles con la de referencia
    private static boolean columnaIgual(BufferedImage img, int x, int xRef, int y0, int y1, int umbral) {
        int iguales = 0;
        for (int y = y0; y <= y1; y++) {
            if (esPixelOscuro(img.getRGB(x, y), umbral) == esPixelOscuro(img.getRGB(xRef, y), umbral)) iguales++;
        }
        return iguales >= (y1 - y0 + 1) * 0.75f;
    }

    private static int[] expandirCodigoVertical(BufferedImage image, int startY, int endY, int centerX, float dpi) {
        int width = image.getWidth();
        int height = image.getHeight();
        int umbralGris = (dpi <= 150) ? 150 : 170;
        int toleranciaEstricta = 5;
        int zonaSilencio = Math.max(8, (int) (dpi / 20));   // ~1,3 mm en blanco = fin del código

        // A lo alto: ZXing da el centro de los patrones de inicio y fin, no los bordes
        int minY = startY, blancos = 0;
        while (minY > 0 && blancos < zonaSilencio) {
            minY--;
            blancos = esPixelOscuro(image.getRGB(centerX, minY), umbralGris) ? 0 : blancos + 1;
        }
        minY += blancos;
        int maxY = endY;
        blancos = 0;
        while (maxY < height - 1 && blancos < zonaSilencio) {
            maxY++;
            blancos = esPixelOscuro(image.getRGB(centerX, maxY), umbralGris) ? 0 : blancos + 1;
        }
        maxY -= blancos;

        // A lo ancho: cada columna del código repite la última aceptada
        int minX = centerX;
        blancos = 0;
        for (int x = centerX - 1; x >= 0 && blancos < toleranciaEstricta; x--) {
            if (columnaIgual(image, x, minX, minY, maxY, umbralGris)) { minX = x; blancos = 0; } else blancos++;
        }
        int maxX = centerX;
        blancos = 0;
        for (int x = centerX + 1; x < width && blancos < toleranciaEstricta; x++) {
            if (columnaIgual(image, x, maxX, minY, maxY, umbralGris)) { maxX = x; blancos = 0; } else blancos++;
        }

        int finalMinY = Math.max(0, minY - 5);
        int finalMaxY = Math.min(height - 1, maxY + 5);
        return new int[]{minX, finalMinY, maxX - minX, finalMaxY - finalMinY};
    }

    private static int[] expandirQR(BufferedImage image, int centerX, int centerY, float dpi) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = centerX, maxX = centerX, minY = centerY, maxY = centerY;
        int toleranciaBlanco = Math.max(20, (int) (dpi * 0.15f));
        int umbralGris = (dpi <= 150) ? 150 : 170;

        // expansión Izquierda
        int blancos = 0;
        while (minX > 0 && blancos < toleranciaBlanco) {
            minX--;
            if (esPixelOscuro(image.getRGB(minX, centerY), umbralGris))
                blancos = 0;
            else
                blancos++;
        }
        minX += blancos;

        // expansión Derecha
        blancos = 0;
        while (maxX < width - 1 && blancos < toleranciaBlanco) {
            maxX++;
            if (esPixelOscuro(image.getRGB(maxX, centerY), umbralGris))
                blancos = 0;
            else
                blancos++;
        }
        maxX -= blancos;

        // expansión Arriba
        blancos = 0;
        while (minY > 0 && blancos < toleranciaBlanco) {
            minY--;
            if (esPixelOscuro(image.getRGB(centerX, minY), umbralGris) ||
                    esPixelOscuro(image.getRGB(minX + 5, minY), umbralGris) ||
                    esPixelOscuro(image.getRGB(maxX - 5, minY), umbralGris))
                blancos = 0;
            else
                blancos++;
        }
        minY += blancos;

        // expansión Abajo
        blancos = 0;
        while (maxY < height - 1 && blancos < toleranciaBlanco) {
            maxY++;
            if (esPixelOscuro(image.getRGB(centerX, maxY), umbralGris) ||
                    esPixelOscuro(image.getRGB(minX + 5, maxY), umbralGris) ||
                    esPixelOscuro(image.getRGB(maxX - 5, maxY), umbralGris))
                blancos = 0;
            else
                blancos++;
        }
        maxY -= blancos;

        return new int[] { minX, minY, maxX - minX, maxY - minY };
    }

    private static boolean esPixelOscuro(int rgb, int umbral) {
        java.awt.Color c = new java.awt.Color(rgb);
        int gris = (c.getRed() + c.getGreen() + c.getBlue()) / 3;
        return gris < umbral;
    }

    private static void eliminarResultadosDuplicados(List<Result> resultados) {
        List<Result> unicos = new ArrayList<>();

        for (Result r : resultados) {
            ResultPoint[] pts = r.getResultPoints();
            if (pts == null || pts.length == 0)
                continue;

            // centro
            float cx = 0, cy = 0;
            for (ResultPoint p : pts) {
                cx += p.getX();
                cy += p.getY();
            }
            cx /= pts.length;
            cy /= pts.length;

            boolean esDuplicado = false;

            for (Result u : unicos) {
                ResultPoint[] uPts = u.getResultPoints();
                float ux = 0, uy = 0;
                for (ResultPoint p : uPts) {
                    ux += p.getX();
                    uy += p.getY();
                }
                ux /= uPts.length;
                uy /= uPts.length;

                // Si < 50 píxeles es el mismo código
                if (Objects.equals(r.getText(), u.getText()) && Math.abs(cx - ux) < 50 && Math.abs(cy - uy) < 50) {
                    esDuplicado = true;
                    break;
                }
            }

            if (!esDuplicado) {
                unicos.add(r);
            }
        }

        resultados.clear();
        resultados.addAll(unicos);
    }

    //private static boolean tieneSuficienteTinta(BufferedImage image) {
    //    int width = image.getWidth();
    //    int height = image.getHeight();
    //    int totalPixels = width * height;
    //    int darkPixels = 0;
    //     for (int y = 0; y < height; y += 5) {
    //         for (int x = 0; x < width; x += 5) {
    //             java.awt.Color c = new java.awt.Color(image.getRGB(x, y));
    //             if ((c.getRed() + c.getGreen() + c.getBlue()) / 3 < 128) {
    //                 darkPixels++;
    //             }
    //         }
    //     }
    //
    //     // calcular % de píxeles oscuros
    //     int pixelesMuestreados = totalPixels / 25;
    //     float porcentajeOscuro = (float) darkPixels / pixelesMuestreados;
    //
    //     // Si < 2% no hay QR/Barcode
    //     return porcentajeOscuro > 0.02f;
    // }

    private static boolean tieneSuficienteTinta(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        int step = 5; 
        
        int pixelesAMuestrear = (width / step) * (height / step);
        
        int pixelesOscurosRequeridos = (int) (pixelesAMuestrear * 0.005f);
        
        if (pixelesOscurosRequeridos == 0) pixelesOscurosRequeridos = 1;

        int darkPixels = 0;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                
                int rgb = image.getRGB(x, y);
                
                int r = (rgb >> 16) & 0xFF; 
                
                if (r < 128) {
                    darkPixels++;
                    
                    // Short-circuit
                    if (darkPixels >= pixelesOscurosRequeridos) {
                        return true; 
                    }
                }
            }
        }

        return false;
    }

    private static boolean verificarTintaEnZona(PDDocument document, int pageIndex, CoordinateDTO coord,
            float dpiScan) {
        PDPage page = document.getPage(pageIndex);
        PDRectangle originalCropBox = page.getCropBox();

        try {
            PDRectangle zona = new PDRectangle(
                    coord.x() - 5, coord.y() - 5,
                    coord.w() + 10, coord.h() + 10);
            page.setCropBox(zona);

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage microImage = renderer.renderImageWithDPI(pageIndex, dpiScan,
                    org.apache.pdfbox.rendering.ImageType.BINARY);

            boolean tieneTinta = tieneSuficienteTinta(microImage);
            microImage.flush();

            return tieneTinta;

        } catch (Exception e) {
            return false;
        } finally {
            page.setCropBox(originalCropBox);
        }
    }

    private static boolean esZonaMargen(CoordinateDTO coord, float pageWidth, float pageHeight) {
        // el 20% exterior de la página se considera margen
        float margenX = pageWidth * 0.20f;
        float margenY = pageHeight * 0.20f;

        boolean enMargenIzquierdo = coord.x() < margenX;
        boolean enMargenDerecho = (coord.x() + coord.w()) > (pageWidth - margenX);
        boolean enMargenInferior = coord.y() < margenY;
        boolean enMargenSuperior = (coord.y() + coord.h()) > (pageHeight - margenY);

        return enMargenIzquierdo || enMargenDerecho || enMargenInferior || enMargenSuperior;
    }

    /**
     * Calcula la resolución (DPI) óptima según tamaño del PDF
     */
    private static float calcularDpiOptimo(PDPage page) {
        PDRectangle box = page.getCropBox();
        float minDimension = Math.min(box.getWidth(), box.getHeight());

        float dpiCalculado = (2500f / minDimension) * 72f;   // A4 ≈ 300 DPI: módulos de 0,2 mm ≈ 2,4 px

        return Math.max(150f, Math.min(dpiCalculado, 300f));
    }

}
