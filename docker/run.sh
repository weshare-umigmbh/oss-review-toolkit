#!/bin/sh

# Get the absolute directory this script resides in.
SCRIPT_DIR="$(cd "$(dirname $0)" && pwd)"

DOCKER_ARGS=$1
ORT_ARGS=$2

(cd $SCRIPT_DIR/.. && \
    . docker/lib && \
    runAsUser "$DOCKER_ARGS" ort "$ORT_ARGS"
)
