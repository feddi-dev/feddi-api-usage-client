#!/bin/bash
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew :feddi-api-usage-client:jcstress "$@"
