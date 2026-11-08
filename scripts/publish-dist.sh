#!/usr/bin/env bash
# Publishes a built APK and its latest.json to the public distribution repo,
# in one git commit.
#
#   scripts/publish-dist.sh <apk> <latest.json> "<commit message>"
#
# Needs GITHUB_TOKEN (or `gh` on PATH). Owner/repo come from GITHUB_OWNER and
# DIST_REPO (defaulting to Villamediana/LauncherAPK); the APK is stored as
# APK_NAME (default launcher.apk).
#
# Why git and not the Contents API: the API takes the file base64-encoded in a
# JSON body, and past ~48 MB of APK (the map widget's native library) GitHub
# answers that PUT with HTTP 422. A plain push takes files up to 100 MB. It is
# also one commit for both files, so the manifest can never point at an APK
# that did not make it up.
#
# The clone is shallow and blob-less, so it downloads no old APKs: only the new
# objects travel, and only upward.
set -euo pipefail

APK="$(realpath "$1")"
MANIFEST="$(realpath "$2")"
MESSAGE="$3"
OWNER="${GITHUB_OWNER:-Villamediana}"
REPO="${DIST_REPO:-LauncherAPK}"
APK_NAME="${APK_NAME:-launcher.apk}"

if [[ -z "${GITHUB_TOKEN:-}" ]] && command -v gh >/dev/null 2>&1; then
    GITHUB_TOKEN="$(gh auth token)"
fi
if [[ -z "${GITHUB_TOKEN:-}" ]]; then
    echo "publish-dist: GITHUB_TOKEN is empty and gh is not available" >&2
    exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --quiet --depth 1 --filter=blob:none --no-checkout \
    "https://x-access-token:${GITHUB_TOKEN}@github.com/${OWNER}/${REPO}.git" "$WORK/dist"
cd "$WORK/dist"
git config user.name "$(git -C "$OLDPWD" config user.name || echo Launcher Release)"
git config user.email "$(git -C "$OLDPWD" config user.email || echo release@localhost)"
# Nothing checked out: the index still knows every file, and only the two
# being replaced are written.
git reset --quiet
cp "$APK" "$APK_NAME"
cp "$MANIFEST" latest.json
git add "$APK_NAME" latest.json
git commit --quiet -m "$MESSAGE"
git push --quiet origin HEAD:main
echo "   pushed $APK_NAME and latest.json to ${OWNER}/${REPO}"
