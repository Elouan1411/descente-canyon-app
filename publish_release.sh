#!/usr/bin/env bash
set -e

# Root directory of the project
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Read version defaults from version.properties if available
VERSION_CODE_DEFAULT=$(grep '^VERSION_CODE=' version.properties 2>/dev/null | cut -d'=' -f2 | tr -d '\r\n')
VERSION_NAME_DEFAULT=$(grep '^VERSION_NAME=' version.properties 2>/dev/null | cut -d'=' -f2 | tr -d '\r\n')

VERSION_CODE="${1:-${VERSION_CODE_DEFAULT:-30}}"
VERSION_NAME="${2:-${VERSION_NAME_DEFAULT:-2.1.0}}"
RELEASE_NOTES="${3:-Nouvelle mise à jour disponible.}"
APK_PATH="${4}"

# Find APK path if not specified
if [ -z "$APK_PATH" ]; then
    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
    elif [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
        APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
    elif [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    else
        echo "❌ Impossible de trouver un fichier APK dans app/build/outputs/apk/"
        echo "💡 Générez d'abord un APK dans Android Studio ou spécifiez son chemin :"
        echo "   ./publish_release.sh 31 \"2.1.1\" \"Notes...\" \"/chemin/vers/mon-app.apk\""
        exit 1
    fi
fi

echo "=========================================="
echo "🚀 Publication de la release Android"
echo "=========================================="
echo "📌 Version Code : $VERSION_CODE"
echo "🏷️  Version Name : $VERSION_NAME"
echo "📝 Notes        : $RELEASE_NOTES"
echo "📦 Fichier APK   : $APK_PATH"
echo "=========================================="

cd backend
npm run publish-release -- --apk="../$APK_PATH" --versionCode="$VERSION_CODE" --versionName="$VERSION_NAME" --notes="$RELEASE_NOTES"
