#!/bin/bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

# Check for Python and pip presence early on
if ! command -v python3 &> /dev/null || ! python3 -m pip --version &> /dev/null; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Python 3 or pip is not installed."
        exit 1
    else
        echo "Warning: Python 3 or pip is not installed. Skipping documentation generation."
        exit 0
    fi
fi

# Fail the build if metadata cannot be extracted
PROJECT_VERSION=$(python3 -c "import xml.etree.ElementTree as ET; tree = ET.parse('pom.xml'); root = tree.getroot(); print([elem.text for elem in root.iter('{http://maven.apache.org/POM/4.0.0}version')][0])")
if [ -z "$PROJECT_VERSION" ]; then echo "Error: Could not extract PROJECT_VERSION"; exit 1; fi

JDK_VERSION=$(python3 -c "import xml.etree.ElementTree as ET; tree = ET.parse('pom.xml'); root = tree.getroot(); print([elem.text for elem in root.iter('{http://maven.apache.org/POM/4.0.0}source')][0])")
if [ -z "$JDK_VERSION" ]; then echo "Error: Could not extract JDK_VERSION"; exit 1; fi

TOMCAT_VERSION=$(grep -oP 'apache-tomcat-\K[0-9\.]+(?=\.tar\.gz)' Dockerfile | head -1)
if [ -z "$TOMCAT_VERSION" ]; then echo "Error: Could not extract TOMCAT_VERSION"; exit 1; fi

PG_VERSION=$(grep -oP 'postgres:\K[0-9]+' docker-compose.yml | head -1)
if [ -z "$PG_VERSION" ]; then echo "Error: Could not extract PG_VERSION"; exit 1; fi

GENERATED_CONTENT="<!-- BEGIN GENERATED REQUIREMENTS -->
> **Note:** The following technical requirements are programmatically generated from the build configuration. Manual modifications will be overwritten.

- **Project Version:** $PROJECT_VERSION
- **JDK Version:** $JDK_VERSION
- **Database:** PostgreSQL $PG_VERSION
- **Application Server:** Tomcat $TOMCAT_VERSION

For non-technical user guides and comprehensive documentation, please visit the [OpenClinica Documentation Portal](https://docs.openclinica.com).
<!-- END GENERATED REQUIREMENTS -->"

# Check if README.md exists and if it was modified manually
if [ -f "README.md" ]; then
    STORED_CHECKSUM=$(tail -n 1 README.md | grep -oP '(?<=<!-- CHECKSUM: )[a-f0-9]+(?= -->)' || echo "")
    if [ ! -z "$STORED_CHECKSUM" ]; then
        ACTUAL_CHECKSUM=$(head -n -1 README.md | md5sum | awk '{print $1}')
        if [ "$STORED_CHECKSUM" != "$ACTUAL_CHECKSUM" ]; then
            echo "Error: README.md was modified manually! Please edit README.template.md instead."
            exit 1
        fi
    else
        echo "Warning: README.md lacks a checksum. It may have been manually modified or created from an older version. Overwriting."
    fi
fi

# Check if docs/explanation/project-info.md exists and if it was modified manually
if [ -f "docs/explanation/project-info.md" ]; then
    STORED_CHECKSUM=$(tail -n 1 docs/explanation/project-info.md | grep -oP '(?<=<!-- CHECKSUM: )[a-f0-9]+(?= -->)' || echo "")
    if [ ! -z "$STORED_CHECKSUM" ]; then
        ACTUAL_CHECKSUM=$(head -n -1 docs/explanation/project-info.md | md5sum | awk '{print $1}')
        if [ "$STORED_CHECKSUM" != "$ACTUAL_CHECKSUM" ]; then
            echo "Error: docs/explanation/project-info.md was modified manually! Please edit README.template.md instead."
            exit 1
        fi
    else
        echo "Warning: docs/explanation/project-info.md lacks a checksum. It may have been manually modified or created from an older version. Overwriting."
    fi
fi

awk -v content="$GENERATED_CONTENT" '{
    gsub(/\{\{GENERATED_REQUIREMENTS\}\}/, content)
    print
}' README.template.md > README.md.tmp

NEW_CHECKSUM=$(md5sum README.md.tmp | awk '{print $1}')
cat README.md.tmp > README.md
echo "<!-- CHECKSUM: $NEW_CHECKSUM -->" >> README.md
rm README.md.tmp

# Install MkDocs and plugins if not present
if ! python3 -c "import mkdocs_redirects" &> /dev/null; then
    if ! python3 -m pip install --user mkdocs mkdocs-material mkdocs-redirects pyyaml; then
        if [ "$STRICT_MODE" = "true" ]; then
            echo "Error: Failed to install Python dependencies."
            exit 1
        else
            echo "Warning: Failed to install Python dependencies. Skipping documentation generation."
            exit 0
        fi
    fi
fi

# Ensure docs directory has the latest README
cp README.md docs/explanation/project-info.md

CURRENT_DATE=$(date +"%Y-%m-%d")

# Update module-level README files with the synchronized project version
for f in core/README.md web/README.md; do
  if [ -f "$f" ]; then
    sed -i "s/\*\*OpenClinica Version:\*\* .*/\*\*OpenClinica Version:\*\* $PROJECT_VERSION/g" "$f"
    sed -i "s/\*\*Updated:\*\* .*/\*\*Updated:\*\* $CURRENT_DATE/g" "$f"
    sed -i "s/This is OpenClinica release .*\./This is OpenClinica release $PROJECT_VERSION./g" "$f"
    sed -i "s/This is a beta release of OpenClinica 3\.1\./This is OpenClinica release $PROJECT_VERSION./g" "$f"
    sed -i "s/\${project\.version}/$PROJECT_VERSION/g" "$f"
    sed -i "s/\${changeSetDate}/$CURRENT_DATE/g" "$f"
  fi
done

# Replace static, deprecated JVM flags in installation guides
find docs/tutorials docs/how-to -type f -name "*.md" -exec sed -i 's/-XX:MaxPermSize=[^ ]* //g; s/ *-XX:+CMSClassUnloadingEnabled//g' {} +

# Validate markdown file locations
APPROVED_DIRS=("^docs/$" "^docs/tutorials/$" "^docs/how-to/$" "^docs/reference/$" "^docs/explanation/$" "^docs/reference/frontend-api/")

for file in $(find docs -name '*.md'); do
    dir_path=$(dirname "$file")"/"
    matched=false
    for pattern in "${APPROVED_DIRS[@]}"; do
        if echo "$dir_path" | grep -Eq "$pattern"; then
            matched=true
            break
        fi
    done
    if [ "$matched" = false ]; then
        echo "Error: Markdown file $file is outside the approved structure ($dir_path)."
        exit 1
    fi
done

# Validate citation formats
MALFORMED_CITATIONS=$(grep -rnEo '\[cite:[^]]+\]' docs/ | grep -vE ':\[cite:[a-zA-Z0-9_-]+(,\s*cite:[a-zA-Z0-9_-]+)*\]$' || true)
if [ ! -z "$MALFORMED_CITATIONS" ]; then
    echo "Error: Malformed citations found:"
    echo "$MALFORMED_CITATIONS"
    exit 1
fi

# Validate Diátaxis structural definition for tutorials
if ! python3 utils/test_validate_prose.py; then
    echo "Error: Prose validation test suite failed."
    if [ "$STRICT_MODE" = "true" ]; then
        exit 1
    else
        echo "Warning: Strict mode disabled. Continuing anyway..."
    fi
fi

if ! python3 utils/validate_prose.py docs/tutorials/*.md; then
    if [ "$STRICT_MODE" = "true" ]; then
        exit 1
    else
        echo "Warning: Prose validation failed. Skipping documentation generation."
        exit 0
    fi
fi

# Validate navigation config for orphaned files
if ! python3 utils/validate_nav.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        exit 1
    else
        echo "Warning: Navigation validation failed. Skipping documentation generation."
        exit 0
    fi
fi

# Validate JSON and XML code snippets
if ! python3 utils/validate_snippets.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Snippet validation failed."
        exit 1
    else
        echo "Warning: Snippet validation failed. Skipping documentation generation."
        exit 0
    fi
fi


# Generate frontend API documentation
if [ -d "web" ] && [ -f "web/package.json" ]; then
    echo "Generating frontend API documentation..."
    cd web
    if ! npm install || ! npm run docs; then
        if [ "$STRICT_MODE" = "true" ]; then
            echo "Error: Frontend API documentation generation failed."
            exit 1
        else
            echo "Warning: Frontend API documentation generation failed. Skipping."
            exit 0
        fi
    fi
    cd ..
fi

# Generate REST API docs (Unified OpenAPI)
if ! python3 utils/merge_openapi.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Python tools failed (merge_openapi.py)."
        exit 1
    else
        echo "Warning: Python tools failed (merge_openapi.py). Skipping documentation generation."
        exit 0
    fi
fi

# Extract static SOAP definitions
if ! python3 utils/extract_soap.py; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Python tools failed (extract_soap.py)."
        exit 1
    else
        echo "Warning: Python tools failed (extract_soap.py). Skipping documentation generation."
        exit 0
    fi
fi

# Build the documentation site
if ! python3 -m mkdocs build; then
    if [ "$STRICT_MODE" = "true" ]; then
        echo "Error: Python tools failed (mkdocs build)."
        exit 1
    else
        echo "Warning: Python tools failed (mkdocs build). Skipping documentation generation."
        exit 0
    fi
fi

# Validate that no placeholders remain in documentation and final outputs
UNRESOLVED=$(find docs core web site -type f \( -name "*.md" -o -name "*.html" \) -not -path "*/node_modules/*" -exec grep -lE '\$\{[a-zA-Z0-9._]+\}' {} + || true)
if [ ! -z "$UNRESOLVED" ]; then
    echo "Error: Unreplaced placeholders found in the following files:"
    echo "$UNRESOLVED"
    exit 1
fi


