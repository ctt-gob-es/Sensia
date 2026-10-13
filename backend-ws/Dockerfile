# syntax=docker/dockerfile:1
# =============================================================================
# backend-ws: servicio SOAP (Jetty + CXF) con OpenCV y Tesseract
# =============================================================================
ARG OPENCV_VERSION=4.13.0


# Etapa 1: OpenCV con bindings de Java
FROM eclipse-temurin:21-jdk-jammy AS opencv
ARG OPENCV_VERSION
# Huella SHA-256 del zip de OpenCV. Al cambiar de versión se recalcula con:
#   curl -sL https://github.com/opencv/opencv/archive/4.13.0.zip | sha256sum
ARG OPENCV_SHA256="431cb489d275da26526581c6735d7d7c0b00f190fe940b3217d386b77aef52c7"

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      cmake make g++ wget unzip python3 ant ca-certificates \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt
RUN wget -q -O opencv.zip "https://github.com/opencv/opencv/archive/${OPENCV_VERSION}.zip" \
 && if [ -n "${OPENCV_SHA256}" ]; then echo "${OPENCV_SHA256}  opencv.zip" | sha256sum -c -; fi \
 && unzip -q opencv.zip

WORKDIR /opt/opencv-${OPENCV_VERSION}/build
RUN cmake -D CMAKE_BUILD_TYPE=RELEASE \
          -D BUILD_JAVA=ON \
          -D BUILD_opencv_python3=OFF \
          -D BUILD_TESTS=OFF -D BUILD_PERF_TESTS=OFF \
          -D BUILD_EXAMPLES=OFF -D BUILD_DOCS=OFF \
          .. \
 && make -j"$(nproc)" \
 && make install


# Etapa 2: compilación con Maven

FROM maven:3.9-eclipse-temurin-21 AS build
ARG OPENCV_VERSION
WORKDIR /app

COPY --from=opencv /usr/local/share/java/opencv4/*.jar /tmp/opencv.jar
RUN mvn -B -q install:install-file -Dfile=/tmp/opencv.jar \
      -DgroupId=org.opencv -DartifactId=opencv -Dversion=${OPENCV_VERSION} -Dpackaging=jar

COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests


# Etapa 3: ejecución
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

ARG OTEL_VERSION="2.31.1"
ARG OTEL_SHA256="sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba"
ADD --checksum=${OTEL_SHA256} \
    https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_VERSION}/opentelemetry-javaagent.jar \
    /opt/otel/agent.jar

RUN apt-get update \
 && chmod 644 /opt/otel/agent.jar \
 && apt-get install -y --no-install-recommends \
      libfontconfig1 libfreetype6 \
      tesseract-ocr tesseract-ocr-spa \
      libglib2.0-0 libsm6 libxext6 libxrender1 \
 && rm -rf /var/lib/apt/lists/*

# OpenCV: librerías nativas y bindings. ldconfig para que el enlazador las encuentre.
COPY --from=opencv /usr/local/lib/ /usr/local/lib/
COPY --from=opencv /usr/local/share/java/opencv4/ /usr/local/share/java/opencv4/
RUN ldconfig

COPY --from=build /app/target/*.jar app.jar

# Usuario sin privilegios con UID fijo; el código queda en solo lectura
RUN groupadd --system --gid 10001 sensia \
 && useradd --system --uid 10001 --gid sensia --no-create-home --shell /usr/sbin/nologin sensiauser \
 && mkdir -p /app/logs /app/temp \
 && chown sensiauser:sensia /app/logs /app/temp
USER 10001

EXPOSE 443 8443

ENTRYPOINT ["java", \
            "-javaagent:/opt/otel/agent.jar", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.library.path=/usr/local/share/java/opencv4/", \
            "-Djava.io.tmpdir=/app/temp", \
            "-jar", "app.jar"]
