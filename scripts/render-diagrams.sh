#!/usr/bin/env bash
# Render all PlantUML diagrams to SVG with the same PlantUML version CI uses.
# Usage: scripts/render-diagrams.sh
set -euo pipefail

PLANTUML_IMAGE="${PLANTUML_IMAGE:-plantuml/plantuml:1.2026.9}"
DIAGRAMS_DIR="$(cd "$(dirname "$0")/../docs/diagrams" && pwd)"

docker run --rm -v "$DIAGRAMS_DIR:/data" -w /data "$PLANTUML_IMAGE" -tsvg -failfast2 -o out "*.puml"
echo "Rendered SVGs to $DIAGRAMS_DIR/out"
