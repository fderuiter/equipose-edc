#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

if ! command -v python3 &> /dev/null; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Python 3 is not installed."
        exit 1
    else
        echo "Warning: Python 3 is not installed. Skipping API doc verification."
        exit 0
    fi
fi

if ! python3 -c "import yaml" &> /dev/null; then
    echo "PyYAML not found, attempting installation..."
    python3 -m pip install pyyaml &> /dev/null || true
fi

echo "Regenerating REST and SOAP specifications..."
if ! python3 utils/merge_openapi.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Failed to merge OpenAPI specs."
        exit 1
    else
        echo "Warning: Failed to merge OpenAPI specs. Skipping API doc verification."
        exit 0
    fi
fi

if ! python3 utils/extract_soap.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Failed to extract SOAP specs."
        exit 1
    else
        echo "Warning: Failed to extract SOAP specs. Skipping API doc verification."
        exit 0
    fi
fi

echo "Checking for undocumented changes in generated specifications..."
if ! git diff --exit-code docs/openapi.json docs/reference/soap.md; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo ""
        echo "ERROR: API documentation drift detected!"
        echo "The generated specifications (docs/openapi.json or docs/reference/soap.md) differ from the committed versions."
        echo "Please commit the updated API documentation files."
        exit 1
    else
        echo ""
        echo "WARNING: API documentation drift detected!"
        echo "The generated specifications differ, but strict mode is disabled. Ignoring."
        exit 0
    fi
fi

echo "API specifications are perfectly aligned with version control."
