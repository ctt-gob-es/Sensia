#!/usr/bin/env bash
set -Eeuo pipefail

cd /work/docs

export PATH="/opt/sensia-docs/node_modules/.bin:${PATH}"
export CHROME_BIN="${CHROME_BIN:-/usr/bin/chromium}"

DOCS=("$@")

if [ "${#DOCS[@]}" -eq 0 ]; then
  DOCS=(
    "src/manual-integrador-sensia-ES.md"
    "src/manual-tecnico-sensia-ES.md"
    "src/manual-usuario-sensia-ES.md"
  )
fi

mkdir -p dist

cat > /tmp/pdf-config-docker.js <<'JS'
const path = require("path");

const config = require(path.join(process.cwd(), "config", "pdf-config.js"));

config.pdf_options = config.pdf_options || {};
config.pdf_options.launch_options = config.pdf_options.launch_options || {};

config.pdf_options.launch_options.executablePath =
  process.env.CHROME_BIN || "/usr/bin/chromium";

const existingArgs = config.pdf_options.launch_options.args || [];

const dockerArgs = [
  "--no-sandbox",
  "--disable-setuid-sandbox",
  "--disable-dev-shm-usage"
];

config.pdf_options.launch_options.args = Array.from(
  new Set([...existingArgs, ...dockerArgs])
);

module.exports = config;
JS

for doc in "${DOCS[@]}"; do
  if [ ! -f "$doc" ]; then
    echo "ERROR: no existe $doc" >&2
    exit 1
  fi

  echo
  echo "==> Actualizando índice: $doc"
  markdown-toc -i "$doc" --maxdepth 3 --bullets "-"
  # Para quitar las líneas en blanco finales que añade markdown-toc
  sed -i -e :a -e '/^\n*$/{$d;N;ba' -e '}' "$doc"

  pdf="${doc%.md}.pdf"

  echo "==> Generando PDF: $pdf"
  rm -f "$pdf"

  md-to-pdf "$doc" --config-file /tmp/pdf-config-docker.js
  pdf_name=$(basename "$pdf")
  mv "$pdf" "dist/$pdf_name"

  echo "OK: dist/$pdf"
done

echo
echo "PDF generados:"
ls -lh dist/*.pdf