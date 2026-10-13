# Guía de contribución y flujo Git

¡Gracias por tu interés en contribuir a este proyecto! Todas las aportaciones, ya sea la corrección de errores, el desarrollo de nuevas características o la mejora de la documentación, son bienvenidas y valoradas.

Para garantizar un entorno de desarrollo organizado, seguro y auditable, te pedimos que revises estas directrices antes de enviar tus cambios.

## Licencia y Aspectos Legales (EUPL)

Este proyecto está distribuido bajo la **European Union Public License (EUPL)**. 

Al enviar una contribución (Pull Request / Merge Request) a este repositorio, certificas que eres el autor original del código (o tienes los derechos para aportarlo) y **aceptas de forma tácita que tus contribuciones se licenciarán bajo los mismos términos de la EUPL**, integrándose en el proyecto de forma libre y colaborativa.

## Gobernanza y Restricciones de Integración

Dado el carácter institucional del proyecto, el ciclo de vida del código sigue un control estricto:

*   **Gestión Centralizada:** Las integraciones definitivas, la validación final y la publicación de nuevas versiones oficiales se gestionan exclusivamente por el equipo de desarrollo del proyecto.
*   **Protección de `main`:** Ningún colaborador puede realizar *commits* ni *merges* directos a la rama `main` de producción. Esta rama está bloqueada y solo recibe actualizaciones certificadas por los mantenedores.
*   **Flujo de Aportaciones:** Toda contribución de código debe enviarse obligatoriamente mediante un *Pull/Merge Request* que apunte **únicamente a la rama `develop`**. 

## Tipos de Contribuciones

*   **Reporte de Errores (Bugs):** Si encuentras un fallo, abre un *Issue* detallando el problema, los pasos para reproducirlo y el entorno donde ocurrió.
*   **Sugerencia de Mejoras:** Antes de programar un cambio arquitectónico grande, abre un *Issue* para debatir su viabilidad con el equipo base.
*   **Desarrollo de Código:** Asegúrate de seguir nuestro modelo de ramas y convenciones de integración explicados a continuación.

## Normas de Convivencia

Mantenemos un entorno de trabajo profesional. Esperamos que todas las interacciones en revisiones de código, *Issues* y debates técnicos se realicen desde el respeto, la crítica constructiva y la colaboración.

---

## 1. Arquitectura de ramas

* **`main`**: Producción (estable). Solo recibe código mediante fusiones certificadas. Usa **etiquetas (tags)** como `v0.1.0` para respaldos históricos, nunca ramas de backup.
* **`develop`**: Integración. Contiene el código base unificado y preparado para la próxima versión.
* **`feature/nombre`**: Ramas de trabajo funcional (ej. `feature/yolox`). Nacen de `develop`.
* **`hotfix/nombre`**: Parches de emergencia. Nacen de `main` y se fusionan tanto en `main` como en `develop`.

## 2. La Regla de oro híbrida para integración

Nuestra política para mantener un historial profesional, trazable y limpio combina dos estrategias:

* **De `feature` a `develop` (Squash):** Al terminar una funcionalidad, sus cambios se "aplastan" en un único commit limpio dentro de `develop`. Esto elimina el ruido de commits temporales o pruebas fallidas.
* **De `develop` a `main` (`--no-ff`):** En los pases a producción, se fuerza un nudo de fusión explícito. Esto mantiene el árbol conectado visualmente y permite auditar exactamente qué código conforma la nueva versión.
```bash
git merge develop --no-ff -m "chore: release v0.2.0"

```



## 3. Flujo de Trabajo Paso a Paso

**1. Arrancar una tarea nueva:**

```bash
git checkout develop
git pull origin develop
git checkout -b feature/nueva-tarea

```

**2. Alinear la feature (línea recta):**
Si `develop` ha recibido código de otros desarrolladores mientras programabas, traslada tus commits a la nueva punta en lugar de cruzar ramas.

```bash
git checkout feature/nueva-tarea
git rebase develop
git push origin feature/nueva-tarea --force

```

**3. Congelar una versión de Producción:**
Tras fusionar a `main` con `--no-ff`, marca el hito inmutable.

```bash
git tag -a v0.2.0 -m "Release v0.2.0: Descripción de cambios"
git push origin v0.2.0

```

## 4. Kit de diagnóstico

Comandos de comprobación y corrección para evitar problemas y auditar el repositorio:

* **Limpiar memoria caché de ramas borradas en el servidor:**
```bash
git fetch --all --prune

```


* **Comprobar las conexiones (Local vs Servidor):**
```bash
git branch -vv

```


* **Visualizar el mapa de conexiones:**
```bash
git log --graph --oneline --decorate --all -n 20

```


* **Descartar todos los cambios locales no guardados:**
```bash
git restore .

```


* **Cancelar una fusión/merge porque hay demasiados conflictos:**
```bash
git merge --abort

```

* **Verificar vulnerabilidades**

```bash
docker run --rm -v trivy-cache:/root/.cache/ -v "$HOME/.m2":/root/.m2:ro -v "$PWD":/src:ro aquasec/trivy:0.75.0 fs --offline-scan --scanners vuln --severity HIGH,CRITICAL --skip-dirs '**/node_modules' --skip-dirs docs/dist /src 2>/dev/null | grep -A12 'Report Summary'
```

* **Regenerar documentacion en PDF**

```bash
LOCAL_UID=$(id -u) LOCAL_GID=$(id -g) make build-docs && cp docs/dist/manual-*-sensia-ES.pdf docs/pdf/
```

* **Generar version para publicar**

```bash
cd /ruta/a/sensia
git checkout develop && git pull --ff-only

V=$(cat VERSION)
CTT=https://github.com/ctt-gob-es/sensia.git

# A partir de la segunda publicación, el commit cuelga del main del CTT
PADRE=()
if git ls-remote --exit-code "$CTT" refs/heads/main >/dev/null; then
  git fetch "$CTT" main && PADRE=(-p FETCH_HEAD)
fi
git branch "ctt/v$V" $(git -c user.name="tunombre" -c user.email="tunombre@tuorganismo.es" \
  commit-tree develop^{tree} "${PADRE[@]}" -m "SensIA v$V (tree $(git rev-parse develop^{tree}))")

git push origin "ctt/v$V"
git push "$CTT" "ctt/v$V:main"
```


Desde una copia limpia del repositorio, el hash debe coincidir con el que aparece en el mensaje del commit anterior.
```bash
git clone https://github.com/ctt-gob-es/sensia.git /tmp/verif && cd /tmp/verif
git rev-parse HEAD^{tree}
git log -1 --format=%B
```

El árbol es idéntico al de develop, así que lo que haya en develop es lo que se publica.


## 5. Versiones y correcciones urgentes

El proyecto sigue [Versionado Semántico](https://semver.org/lang/es/) (`MAYOR.MENOR.PARCHE`):

- **PARCHE** (`1.0.0` → `1.0.1`): correcciones, incluidas las urgentes de seguridad.
- **MENOR** (`1.0.1` → `1.1.0`): funcionalidades nuevas compatibles.
- **MAYOR** (`1.1.0` → `2.0.0`): cambios incompatibles, en particular en el contrato SOAP (WSDL).
- **Candidata** (`1.0.0-rc.1` → `1.0.0-rc.2`): cualquier cambio mientras la versión no es definitiva.

Una versión publicada no se modifica: cualquier cambio posterior sale con un número nuevo, y sus etiquetas no se mueven ni se borran.

**Corrección urgente (hotfix)**

```bash
git checkout main && git pull --ff-only
git checkout -b hotfix/descripcion
# corrección y commit
echo 1.0.1 > VERSION && make version    # y sección "## [1.0.1] - AAAA-MM-DD" en CHANGELOG.md
git commit -am "chore: versión 1.0.1"
git checkout main && git merge --no-ff hotfix/descripcion -m "chore: release v1.0.1"
git tag -a v1.0.1 -m "SensIA 1.0.1" && git push origin main v1.0.1
git checkout develop && git merge --no-ff hotfix/descripcion && git push origin develop
git branch -d hotfix/descripcion
```

Si `develop` ya prepara otra versión, en los conflictos se conserva el `VERSION` de `develop` y en `CHANGELOG.md` se mantienen las dos secciones.