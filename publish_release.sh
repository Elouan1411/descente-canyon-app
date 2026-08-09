#!/usr/bin/env bash
set -e

# Root directory of the project
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Parse command line arguments intelligently
APK_PATH=""
VERSION_CODE_ARG=""
VERSION_NAME_ARG=""
RELEASE_NOTES_ARG=""

for arg in "$@"; do
    if [[ "$arg" == *.apk ]]; then
        APK_PATH="$arg"
    elif [[ -z "$VERSION_CODE_ARG" && "$arg" =~ ^[0-9]+$ ]]; then
        VERSION_CODE_ARG="$arg"
    elif [[ -z "$VERSION_NAME_ARG" && "$arg" =~ ^[0-9]+\.[0-9]+ ]]; then
        VERSION_NAME_ARG="$arg"
    else
        RELEASE_NOTES_ARG="$arg"
    fi
done

# Read current version from version.properties
VERSION_CODE_CURRENT=$(grep '^VERSION_CODE=' version.properties 2>/dev/null | cut -d'=' -f2 | tr -d '\r\n')
VERSION_NAME_CURRENT=$(grep '^VERSION_NAME=' version.properties 2>/dev/null | cut -d'=' -f2 | tr -d '\r\n')

if [ -z "$VERSION_CODE_CURRENT" ]; then VERSION_CODE_CURRENT=36; fi
if [ -z "$VERSION_NAME_CURRENT" ]; then VERSION_NAME_CURRENT="2.1.6"; fi

# If explicit version code passed OR if --bump flag passed
if [[ "$*" == *"--bump"* ]]; then
    NEW_VERSION_CODE=$((VERSION_CODE_CURRENT + 1))
    MAJOR=$(echo "$VERSION_NAME_CURRENT" | cut -d. -f1)
    MINOR=$(echo "$VERSION_NAME_CURRENT" | cut -d. -f2)
    PATCH=$(echo "$VERSION_NAME_CURRENT" | cut -d. -f3)
    if [ -z "$PATCH" ]; then PATCH=0; fi
    NEW_PATCH=$((PATCH + 1))
    NEW_VERSION_NAME="${MAJOR}.${MINOR}.${NEW_PATCH}"

    echo "VERSION_CODE=${NEW_VERSION_CODE}" > version.properties
    echo "VERSION_NAME=${NEW_VERSION_NAME}" >> version.properties

    echo "🔄 Bump de version dans version.properties :"
    echo "   VERSION_CODE : ${VERSION_CODE_CURRENT} ➡️ ${NEW_VERSION_CODE}"
    echo "   VERSION_NAME : ${VERSION_NAME_CURRENT} ➡️ ${NEW_VERSION_NAME}"

    VERSION_CODE="$NEW_VERSION_CODE"
    VERSION_NAME="$NEW_VERSION_NAME"
elif [ -n "$VERSION_CODE_ARG" ]; then
    VERSION_CODE="$VERSION_CODE_ARG"
    VERSION_NAME="${VERSION_NAME_ARG:-$VERSION_NAME_CURRENT}"
else
    # Default: Use the version code and name from version.properties (matching Android Studio build)
    VERSION_CODE="$VERSION_CODE_CURRENT"
    VERSION_NAME="$VERSION_NAME_CURRENT"
fi

RELEASE_NOTES="${RELEASE_NOTES_ARG:-Mise à jour v${VERSION_NAME}}"
# Remove --bump from release notes if it captured it
RELEASE_NOTES=$(echo "$RELEASE_NOTES" | sed 's/--bump//g' | xargs)
if [ -z "$RELEASE_NOTES" ]; then RELEASE_NOTES="Mise à jour v${VERSION_NAME}"; fi

# Find APK path if not explicitly provided
if [ -z "$APK_PATH" ]; then
    if [ -f "app/release/app-release.apk" ]; then
        APK_PATH="app/release/app-release.apk"
    elif [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    elif [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
        APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    elif [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    else
        echo "❌ Impossible de trouver un fichier APK."
        echo "💡 Générez d'abord votre APK dans Android Studio."
        exit 1
    fi
fi

# Resolve absolute path for APK to avoid issues when switching directory to backend
if [[ "$APK_PATH" != /* ]]; then
    FINAL_APK_PATH="$(pwd)/$APK_PATH"
else
    FINAL_APK_PATH="$APK_PATH"
fi

if [ ! -f "$FINAL_APK_PATH" ]; then
    echo "❌ Fichier APK introuvable : $FINAL_APK_PATH"
    exit 1
fi

echo "=========================================="
echo "🚀 Publication de la release Android"
echo "=========================================="
echo "📌 Version Code : $VERSION_CODE"
echo "🏷️  Version Name : $VERSION_NAME"
echo "📝 Notes        : $RELEASE_NOTES"
echo "📦 Fichier APK   : $FINAL_APK_PATH"
echo "=========================================="

cd backend
if [ ! -d "node_modules/@vercel/blob" ]; then
    echo "📦 Installation des dépendances du backend..."
    npm install --silent
fi
npm run publish-release -- --apk="$FINAL_APK_PATH" --versionCode="$VERSION_CODE" --versionName="$VERSION_NAME" --notes="$RELEASE_NOTES"

cd "$SCRIPT_DIR"
echo "=========================================="
echo "🐙 Git Commit & Push du bump de version"
echo "=========================================="
git add version.properties
git commit -m "chore(release): bump version to v${VERSION_NAME} (build ${VERSION_CODE})"
git push origin main

echo "✨ Release v${VERSION_NAME} (build ${VERSION_CODE}) publiée et poussée sur GitHub !"
