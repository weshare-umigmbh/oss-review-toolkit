#!/bin/sh

# Get the absolute directory this script resides in.
SCRIPT_DIR="$(cd "$(dirname $0)" && pwd)"

(cd $SCRIPT_DIR/.. && \
    . docker/lib && \
    buildWithoutContext docker/build/Dockerfile ort-build:latest && \
    runGradleTask ort-build :cli:distTar
)
