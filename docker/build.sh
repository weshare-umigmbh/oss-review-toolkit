#/bin/sh

# Get the absolute directory this script resides in.
SCRIPT_DIR="$(cd "$(dirname $0)" && pwd)"

(cd $SCRIPT_DIR/.. \
    && . docker/lib \
    && buildAndRun docker/dockerfile-to-build-ort ort-build:latest installDist
)
