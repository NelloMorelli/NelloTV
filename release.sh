#!/bin/zsh
# Build Nello TV and publish it as a GitHub release; the TV offers the update by itself.
#   ./release.sh "what changed"
set -e
cd "$(dirname "$0")"
if [ -n "$(git status --porcelain)" ]; then
  git add -A
  git commit -qm "${1:-Update}"
fi
./gradlew -q assembleRelease
V=$(git rev-list --count HEAD)
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "build/NelloTV-v$V.apk"
git push -q origin main
gh release create "v$V" "build/NelloTV-v$V.apk" --title "Nello TV v$V" --notes "${1:-Aggiornamento}"
echo "Pubblicata la versione $V"
