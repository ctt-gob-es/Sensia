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

package io.github.sensia.ws.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.net.URLEncoder;
import java.util.Scanner;
import java.util.regex.Matcher;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.github.sensia.ws.generated.RedactionItem;

public class TestUtil {

    public static String getBaseName(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? fileName : fileName.substring(0, dotIndex);
    }

    public static String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex <= 0 ? "" : fileName.substring(dotIndex);
    }

    public static synchronized void guardarInforme(String pathToFile, String testId, String archivo,
            List<RedactionItem> res, long tiempoMs) {
        File f = new File(pathToFile);
        boolean esNuevo = !f.exists();

        try (PrintWriter out = new PrintWriter(new BufferedWriter(new FileWriter(f, true)))) {
            if (esNuevo) {
                out.println(
                        "==========================================================================================================");
                out.println("                      REPORTE DE EJECUCIÓN SENSIA - " + new java.util.Date());
                out.println(
                        "==========================================================================================================");
                out.println(String.format("%-12s | %-25s | %-12s | %-10s | %-10s | %-30s",
                        "ID TEST", "ARCHIVO", "ACCIÓN", "TIPO", "TIEMPO", "DETALLE/TEXTO"));
                out.println(
                        "----------------------------------------------------------------------------------------------------------");
            }

            if (res != null && !res.isEmpty()) {
                for (var r : res) {
                    out.println(String.format("%-12s | %-25s | %-12s | %-10s | %-10s | %-30s",
                            testId,
                            truncar(archivo, 25),
                            r.getAction(),
                            r.getDetectionType(),
                            tiempoMs + " ms",
                            r.getText() != null ? r.getText() : r.getDescription()));
                }
            } else {
                out.println(String.format("%-12s | %-25s | %-12s | %-10s | %-10s | %-30s",
                        testId, truncar(archivo, 25), "SIN_DETECCION", "-", tiempoMs + " ms",
                        "No se encontraron patrones"));
            }
        } catch (IOException e) {
            System.err.println("Error al escribir informe: " + e.getMessage());
        }
    }

    private static String truncar(String t, int n) {
        return (t.length() > n) ? t.substring(0, n - 3) + "..." : t;
    }

    public static String cambiarExtension(String nombreArchivo, String nuevaExtension) {
        if (!nuevaExtension.startsWith(".")) {
            nuevaExtension = "." + nuevaExtension;
        }

        int puntoIndex = nombreArchivo.lastIndexOf(".");
        
        if (puntoIndex == -1) {
            return nombreArchivo + nuevaExtension;
        }
        
        return nombreArchivo.substring(0, puntoIndex) + nuevaExtension;
    }


    public static void guardarFichero(String pathToFile, byte[] contenido) {
        try {
            Path filePath = Paths.get(pathToFile);

            Files.write(filePath, contenido);
            System.out.println("   [OK] Resultado generado en: " + filePath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("   [!] Error al guardar el resultado: " + e.getMessage());
        }
    }

    public static void manejarExcepcion(String testId, Exception e) {
        System.err.println("\n[!!!] ERROR EN TEST " + testId + " [!!!]");
        System.err.println("Detalle: " + e.getMessage());
        if (e instanceof jakarta.xml.ws.soap.SOAPFaultException) {
            jakarta.xml.ws.soap.SOAPFaultException sfe = (jakarta.xml.ws.soap.SOAPFaultException) e;
            System.err.println("Código de Error SOAP: " + sfe.getFault().getFaultCode());
        }
    }

    public static void generarPDF(String htmlPath, String pathToDst) {

        try (OutputStream os = new FileOutputStream(pathToDst)) {
            String content = new String(Files.readAllBytes(Paths.get(htmlPath)));

            String baseUri = new File(htmlPath).getParentFile().toURI().toString();

            PdfRendererBuilder builder = new PdfRendererBuilder();

            builder.withHtmlContent(content, baseUri);
            builder.toStream(os);
            builder.run();
            System.out.println("✅ PDF generado con éxito en: " + pathToDst);
        } catch (Exception e) {
            System.err.println("❌ Error al generar el PDF: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void prepararDirectorioDataset(File directorio, int cantidadEsperada, Runnable funcionDescarga) {
        boolean necesitaDescarga = true;

        if (directorio.exists() && directorio.isDirectory()) {
            File[] archivos = directorio.listFiles();
            int cantidadActual = (archivos != null) ? archivos.length : 0;

            if (cantidadActual == cantidadEsperada) {
                necesitaDescarga = false;
                System.out.println("[INFO] Las " + cantidadEsperada + " imágenes ya están en '" + directorio.getName()
                        + "'. Omitiendo descarga.");
            } else {
                System.out.println("[INFO] Carpeta incompleta o alterada (" + cantidadActual + "/" + cantidadEsperada
                        + "). Limpiando...");
                if (archivos != null) {
                    for (File f : archivos) {
                        f.delete();
                    }
                }
            }
        } else {
            directorio.mkdirs();
        }

        if (necesitaDescarga) {
            System.out.println("[INFO] Ejecutando fuente de descarga para " + cantidadEsperada + " archivos...");
            try {
                funcionDescarga.run();
            } catch (Exception e) {
                System.err.println("[ERROR] Falló la descarga del dataset. " + e.getMessage());
                throw new RuntimeException("Cancelando: No se pudo preparar el dataset en " + directorio.getPath(), e);
            }
        }
    }

    public static void descargarListaPdfs(java.util.List<String> urls, String carpetaDestino) {
        File dir = new File(carpetaDestino);
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("❌ Error al crear carpeta: " + carpetaDestino);
            return;
        }

        int exitos = 0;
        for (int i = 0; i < urls.size(); i++) {
            String urlStr = urls.get(i).trim();
            if (urlStr.isEmpty())
                continue;

            File destFile = new File(dir, "documento_legal_" + (i + 1) + ".pdf");
            System.out.println("[" + (i + 1) + "/" + urls.size() + "] Conectando a: " + urlStr);

            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) java.net.URI.create(urlStr).toURL()
                        .openConnection();

                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "application/pdf, application/octet-stream");
                conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
                conn.setRequestProperty("Referer", "https://www.poderjudicial.es/search/indexAN.jsp");
                conn.setRequestProperty("Connection", "keep-alive");

                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);

                int status = conn.getResponseCode();

                if (status == java.net.HttpURLConnection.HTTP_MOVED_TEMP
                        || status == java.net.HttpURLConnection.HTTP_MOVED_PERM
                        || status == java.net.HttpURLConnection.HTTP_SEE_OTHER) {
                    String newUrl = conn.getHeaderField("Location");
                    conn = (java.net.HttpURLConnection) java.net.URI.create(newUrl).toURL().openConnection();
                    conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    conn.setRequestProperty("Accept", "application/pdf");
                    status = conn.getResponseCode();
                }

                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    String contentType = conn.getContentType();

                    if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                        System.err.println(
                                "  -> ❌ ERROR: El servidor devolvió una página web (HTML), no un PDF. (Posible bloqueo o sesión caducada)");
                        continue;
                    }

                    try (java.io.InputStream in = conn.getInputStream();
                            java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }

                        if (destFile.length() < 1024) {
                            System.err.println("  -> ⚠️ AVISO: El archivo guardado es sospechosamente pequeño ("
                                    + destFile.length() + " bytes).");
                        } else {
                            System.out.println("  -> ✅ Guardado: " + destFile.getName() + " ("
                                    + (destFile.length() / 1024) + " KB)");
                            exitos++;
                        }
                    }
                } else {
                    System.err.println("  -> ❌ Fallo: El servidor respondió con HTTP " + status);
                }
            } catch (Exception e) {
                System.err.println("  -> ❌ Error de red: " + e.getMessage());
            }
        }
        System.out.println("✅ Proceso terminado. " + exitos + " PDFs válidos descargados en: " + dir.getAbsolutePath());
    }

    public static java.util.List<String> leerUrlsDesdeFichero(String rutaFichero) throws java.io.IOException {
        java.nio.file.Path path = java.nio.file.Paths.get(rutaFichero);

        if (!java.nio.file.Files.exists(path)) {
            throw new java.io.FileNotFoundException("❌ No se encontró el fichero de URLs en: " + path.toAbsolutePath());
        }

        return java.nio.file.Files.readAllLines(path).stream()
                .map(String::trim)
                .filter(linea -> !linea.isEmpty() && !linea.startsWith("#"))
                .collect(java.util.stream.Collectors.toList());
    }

    public static void consultarWipedia(String busqueda, int cantidad, String rutaFichero) {
        System.out.println("PASO 1: Buscando " + cantidad + " imágenes de '" + busqueda + "'...");

        try {
            String busquedaCodificada = URLEncoder.encode(busqueda, StandardCharsets.UTF_8.name());
            String apiUrl = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch="
                    + busquedaCodificada + "&gsrnamespace=6&prop=imageinfo&iiprop=url&gsrlimit="
                    + cantidad + "&format=json";

            HttpURLConnection apiConn = (HttpURLConnection) java.net.URI.create(apiUrl).toURL().openConnection();
            apiConn.setRequestProperty("User-Agent", "MiDescargadorJava/1.0 (tuemail@ejemplo.com)");

            String jsonResp;
            try (Scanner s = new Scanner(apiConn.getInputStream(), StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                jsonResp = s.hasNext() ? s.next() : "";
            }

            List<String> urlsA_Guardar = new ArrayList<>();
            Matcher mUrl = Pattern.compile("\"url\":\"(https[^\"]+)\"").matcher(jsonResp);

            while (mUrl.find() && urlsA_Guardar.size() < cantidad) {
                String imageUrl = mUrl.group(1).replace("\\/", "/");
                if (imageUrl.matches("(?i).*\\.(jpg|jpeg|png|webp)$")) {
                    urlsA_Guardar.add(imageUrl);
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(rutaFichero))) {
                for (String url : urlsA_Guardar) {
                    writer.write(url);
                    writer.newLine();
                }
            }

            System.out.println("✅ Fichero '" + rutaFichero + "' generado con " + urlsA_Guardar.size() + " URLs.");

        } catch (Exception e) {
            System.err.println("❌ Error al generar el fichero de URLs: " + e.getMessage());
        }
    }

    public static void descargarWikipedia(String rutaFicheroUrls, String carpetaDestino, String prefijoFichero) {
        System.out.println("\nPASO 2: Leyendo URLs desde '" + rutaFicheroUrls + "' e iniciando descarga...");

        try {
            File dir = new File(carpetaDestino);
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("❌ Error al crear carpeta: " + carpetaDestino);
                return;
            }

            List<String> urls = Files.readAllLines(Paths.get(rutaFicheroUrls), StandardCharsets.UTF_8);

            if (urls.isEmpty()) {
                System.out.println("⚠️ El fichero de URLs está vacío.");
                return;
            }

            int count = 0;
            for (String imageUrl : urls) {
                if (imageUrl.trim().isEmpty())
                    continue;

                String ext = imageUrl.substring(imageUrl.lastIndexOf("."));
                File destFile = new File(dir, prefijoFichero + count + ext);

                try {
                    HttpURLConnection dlConn = (HttpURLConnection) java.net.URI.create(imageUrl).toURL().openConnection();
                    dlConn.setRequestProperty("User-Agent", "MiDescargadorJava/1.0 (tuemail@ejemplo.com)");
                    dlConn.setConnectTimeout(5000);
                    dlConn.setReadTimeout(10000);

                    if (dlConn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        System.err.println("  -> ⚠️ Error HTTP " + dlConn.getResponseCode() + " en: " + imageUrl);
                        continue;
                    }

                    try (InputStream in = dlConn.getInputStream();
                            FileOutputStream out = new FileOutputStream(destFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }

                    System.out
                            .println("[" + (count + 1) + "/" + urls.size() + "] 📸 Descargada: " + destFile.getName());
                    count++;

                    // error 429: pausa entre descargas
                    Thread.sleep(500);

                } catch (Exception ex) {
                    System.err.println("  -> ❌ Error descargando " + imageUrl + ": " + ex.getMessage());
                }
            }

            System.out.println("🚀 ¡Proceso completado con éxito en: " + dir.getAbsolutePath() + "!");

        } catch (Exception e) {
            System.err.println("❌ Error crítico procesando la descarga: " + e.getMessage());
        }
    }

    public static String getPathResource(String nombreRecurso) {
        java.net.URL recurso = TestUtil.class.getClassLoader().getResource(nombreRecurso);

        if (recurso == null) {
            throw new IllegalArgumentException("❌ No se encontró '" + nombreRecurso + "' en la carpeta resources.");
        }

        try {
            return new java.io.File(recurso.toURI()).getAbsolutePath();
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("Error al parsear la ruta URI del recurso: " + nombreRecurso, e);
        }
    }


    public static String anadirSufijo(String fileName, String sufijo) {
        if (fileName == null) return "documento" + sufijo;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) return fileName + sufijo;
        return fileName.substring(0, dotIndex) + sufijo + fileName.substring(dotIndex);
    }


}
