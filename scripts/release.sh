#!/usr/bin/env bash
# Cuts a new Launcher release end-to-end.
#
# Bumps versionCode / versionName in app/build.gradle.kts, runs the unit
# tests, builds the signed release APK, and publishes both the APK and a
# refreshed latest.json to the PUBLIC distribution repo (LauncherAPK)
# via the GitHub Contents API — no local checkout of that repo is needed.
# Then commits the version bump in this (private) source repo and pushes.
#
# The in-app updater polls the public repo anonymously, so the launcher
# ships with no token embedded and users need none.
#
# Fails loudly and early: nothing gets committed unless the tests pass and
# the APK builds; nothing gets pushed if any step upstream broke.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- toolchain --
if [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    if [[ -x "$HOME/.toolchain/jdk-21.0.12.1+1/bin/java" ]]; then
        export JAVA_HOME="$HOME/.toolchain/jdk-21.0.12.1+1"
    elif command -v java >/dev/null 2>&1; then
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    else
        echo "Error: Java not found. Set JAVA_HOME to a JDK 17 or newer." >&2
        exit 3
    fi
fi
if [[ ! -d "${ANDROID_HOME:-}/platforms/android-36" ]]; then
    if [[ -d "${ANDROID_SDK_ROOT:-}/platforms/android-36" ]]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ -d "$HOME/Android/Sdk/platforms/android-36" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    else
        echo "Error: Android SDK 36 not found. Set ANDROID_HOME or ANDROID_SDK_ROOT." >&2
        exit 3
    fi
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# The wrapper, not a gradle on the PATH. A standalone install is one more
# thing that has to exist on the machine and agree with the version this repo
# expects, and when it did not — a rebuilt toolchain, a different box — the
# failure landed as "Tests failed" on a line that had never run a test.
GRADLE="$REPO_ROOT/gradlew"

# --------------------------------------------------- GitHub distribution -----
# The token is used only by this script to upload the APK and manifest to the
# public distribution repo. The source push uses the machine's configured Git
# credentials. Nothing here is shipped in the launcher.
#
# Prefer an explicit environment secret, then GitHub CLI's credential store.
# Never scrape a token from the remote URL: that encourages leaving credentials
# in .git/config where unrelated tooling and diagnostics can expose them.
GITHUB_TOKEN="${GITHUB_TOKEN:-}"
GITHUB_OWNER="Villamediana"
DIST_REPO="LauncherAPK"

# --------------------------------------------------------------------- args --
BUMP="patch"
PUSH=1
SKIP_TESTS=0
NOTES=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [options] "release notes"

Options:
  --patch          Bump the patch number (default). 1.24.0 -> 1.24.1
  --minor          Bump the minor number.           1.24.5 -> 1.25.0
  --major          Bump the major number.           1.24.0 -> 2.0.0
  --no-push        Commit locally but do not push anything.
  --skip-tests     Skip :app:testDebugUnitTest. Use only when you have just
                   run them and know the tree is clean.
  -h, --help       Show this help.

The release notes are shown in the in-app updater, so keep them one or two
short sentences. Wrap in quotes.

Example:
  scripts/release.sh --minor "Learned flows, memory pointers, and a self-update."
EOF
}

while (( "$#" )); do
    case "$1" in
        --patch) BUMP="patch"; shift ;;
        --minor) BUMP="minor"; shift ;;
        --major) BUMP="major"; shift ;;
        --no-push) PUSH=0; shift ;;
        --skip-tests) SKIP_TESTS=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
        *)
            if [[ -z "$NOTES" ]]; then
                NOTES="$1"
            else
                echo "Extra argument (release notes should be one quoted string): $1" >&2
                exit 2
            fi
            shift
            ;;
    esac
done

if [[ -z "$NOTES" ]]; then
    echo "Error: release notes are required." >&2
    echo >&2
    usage >&2
    exit 2
fi

# Help and local --no-push releases do not need network credentials. Resolve
# the token only after parsing arguments so those paths work on an offline
# development machine.
if [[ $PUSH -eq 1 && -z "$GITHUB_TOKEN" ]] && command -v gh >/dev/null 2>&1; then
    GITHUB_TOKEN="$(gh auth token 2>/dev/null || true)"
fi
if [[ $PUSH -eq 1 && -z "$GITHUB_TOKEN" ]]; then
    echo "Error: could not find a GitHub token." >&2
    echo "Set GITHUB_TOKEN in the environment or authenticate with: gh auth login" >&2
    exit 3
fi

# ---------------------------------------------------------------- pre-check --
GRADLE_FILE="app/build.gradle.kts"
APK_OUT="app/build/outputs/apk/release/app-release.apk"

if [[ ! -f "$GRADLE_FILE" ]]; then
    echo "Cannot find $GRADLE_FILE. Are we in the repo root?" >&2
    exit 3
fi

# Refuse to release from a dirty tree. The commit that follows would sweep in
# whatever else is uncommitted, which is the kind of accident a release script
# should not enable.
if [[ -n "$(git status --porcelain -uno)" ]]; then
    echo "Working tree has uncommitted changes:" >&2
    git status --short >&2
    echo >&2
    echo "Commit or stash them before running a release." >&2
    exit 3
fi

CURRENT_CODE=$(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | head -1 | awk '{print $3}')
CURRENT_NAME=$(grep -oE 'versionName = "[^"]+"' "$GRADLE_FILE" | head -1 | sed -E 's/versionName = "(.*)"/\1/')

if [[ -z "$CURRENT_CODE" || -z "$CURRENT_NAME" ]]; then
    echo "Could not parse versionCode/versionName out of $GRADLE_FILE." >&2
    exit 4
fi

# --------------------------------------------------- version arithmetic ------
IFS='.' read -r MAJ MIN PAT <<< "$CURRENT_NAME"
: "${MAJ:=0}" "${MIN:=0}" "${PAT:=0}"

case "$BUMP" in
    patch) PAT=$((PAT + 1)) ;;
    minor) MIN=$((MIN + 1)); PAT=0 ;;
    major) MAJ=$((MAJ + 1)); MIN=0; PAT=0 ;;
esac
NEW_NAME="${MAJ}.${MIN}.${PAT}"
NEW_CODE=$((CURRENT_CODE + 1))

echo "==============================================================="
echo "Release plan"
echo "==============================================================="
printf "  versionCode  %s -> %s\n" "$CURRENT_CODE" "$NEW_CODE"
printf "  versionName  %s -> %s\n" "$CURRENT_NAME" "$NEW_NAME"
printf "  bump         %s\n" "$BUMP"
printf "  publish to   %s (public)\n" "${GITHUB_OWNER}/${DIST_REPO}"
printf "  push source  %s\n" "$([[ $PUSH -eq 1 ]] && echo yes || echo NO)"
printf "  notes        %s\n" "$NOTES"
echo "==============================================================="
echo

# ----------------------------------------------- edit build.gradle.kts -------
sed -i -E "s/versionCode = ${CURRENT_CODE}\b/versionCode = ${NEW_CODE}/" "$GRADLE_FILE"
sed -i -E "s/versionName = \"${CURRENT_NAME//./\\.}\"/versionName = \"${NEW_NAME}\"/" "$GRADLE_FILE"

if ! grep -q "versionCode = ${NEW_CODE}" "$GRADLE_FILE"; then
    echo "sed did not update versionCode. Reverting." >&2
    git checkout -- "$GRADLE_FILE"
    exit 5
fi
if ! grep -q "versionName = \"${NEW_NAME}\"" "$GRADLE_FILE"; then
    echo "sed did not update versionName. Reverting." >&2
    git checkout -- "$GRADLE_FILE"
    exit 5
fi

# ------------------------------------------------------------- tests ---------
if [[ $SKIP_TESTS -eq 0 ]]; then
    echo ">> Running unit tests and Android lint"
    if ! "$GRADLE" :app:testDebugUnitTest :app:lintDebug; then
        echo "Tests or lint failed. Reverting version bump." >&2
        git checkout -- "$GRADLE_FILE"
        exit 6
    fi
fi

# ------------------------------------------------------------- build ---------
echo ">> Building release APK"
# A release without the signing properties produces an unsigned file under a
# different name. Remove the expected output first so a signed APK left by an
# older build can never be mistaken for the release that was just requested.
rm -f "$APK_OUT"
if ! "$GRADLE" :app:assembleRelease; then
    echo "Release build failed. Reverting version bump." >&2
    git checkout -- "$GRADLE_FILE"
    exit 7
fi

if [[ ! -f "$APK_OUT" ]]; then
    echo "Expected APK at $APK_OUT but it is missing." >&2
    git checkout -- "$GRADLE_FILE"
    exit 8
fi

# Verify both identity and signing before this file crosses the network. This
# also catches stale Gradle outputs and a release accidentally built from a
# different application id.
BUILD_TOOLS_DIR=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -print \
    | sort -V | tail -1)
APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
AAPT="$BUILD_TOOLS_DIR/aapt"
if [[ ! -x "$APKSIGNER" || ! -x "$AAPT" ]]; then
    echo "Android build tools are incomplete: apksigner/aapt not found." >&2
    git checkout -- "$GRADLE_FILE"
    exit 8
fi
if ! "$APKSIGNER" verify "$APK_OUT"; then
    echo "Release APK is not correctly signed." >&2
    git checkout -- "$GRADLE_FILE"
    exit 8
fi
APK_BADGING=$("$AAPT" dump badging "$APK_OUT" | sed -n '1p')
APK_PACKAGE=$(printf '%s' "$APK_BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
APK_CODE=$(printf '%s' "$APK_BADGING" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")
APK_NAME=$(printf '%s' "$APK_BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
if [[ "$APK_PACKAGE" != "com.villamediana.launcher" || "$APK_CODE" != "$NEW_CODE" || "$APK_NAME" != "$NEW_NAME" ]]; then
    echo "Release APK identity mismatch: package=$APK_PACKAGE code=$APK_CODE name=$APK_NAME" >&2
    git checkout -- "$GRADLE_FILE"
    exit 8
fi

# ------------------------------------------------- checksum + manifest -------
NEW_SHA=$(sha256sum "$APK_OUT" | cut -d' ' -f1)
APK_SIZE=$(stat -c%s "$APK_OUT" 2>/dev/null || wc -c < "$APK_OUT")
echo ">> APK ready ($((APK_SIZE / 1024 / 1024)) MB) · sha256 $NEW_SHA"

# JSON generated by hand rather than jq so a repo without jq installed still
# releases. The single field that could carry unusual characters is `notes`;
# quotes and backslashes are escaped before it lands in the manifest.
ESCAPED_NOTES=$(printf '%s' "$NOTES" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')

MANIFEST_TMP=$(mktemp)
cat > "$MANIFEST_TMP" <<JSON
{
  "versionCode": ${NEW_CODE},
  "versionName": "${NEW_NAME}",
  "notes": "${ESCAPED_NOTES}",
  "apk": "https://raw.githubusercontent.com/${GITHUB_OWNER}/${DIST_REPO}/main/launcher.apk",
  "sha256": "${NEW_SHA}"
}
JSON

# ---------------------------------------- upload to the distribution repo ----
# Uses the GitHub Contents API — one PUT per file, base64 in the body,
# with the current file SHA in `sha` for updates (empty on first create).
#
# Two HTTP calls per file: GET to learn the current SHA (404 → new file),
# PUT to upload. No local checkout of the distribution repo is needed.

api_get_sha() {
    local path="$1"
    local url="https://api.github.com/repos/${GITHUB_OWNER}/${DIST_REPO}/contents/${path}"
    curl -sS -H "Authorization: Bearer $GITHUB_TOKEN" \
             -H "Accept: application/vnd.github+json" \
             "$url" \
        | grep -oE '"sha": "[a-f0-9]+"' | head -1 | cut -d'"' -f4 || true
}

api_put_file() {
    local path="$1"
    local source_file="$2"
    local message="$3"

    local existing_sha
    existing_sha=$(api_get_sha "$path")

    local content_b64
    content_b64=$(base64 -w0 < "$source_file")
    local source_blob_sha
    source_blob_sha=$(git hash-object "$source_file")

    local payload_file
    payload_file=$(mktemp)
    if [[ -n "$existing_sha" ]]; then
        printf '{"message":"%s","content":"%s","sha":"%s","branch":"main"}' \
            "$message" "$content_b64" "$existing_sha" > "$payload_file"
    else
        printf '{"message":"%s","content":"%s","branch":"main"}' \
            "$message" "$content_b64" > "$payload_file"
    fi

    local url="https://api.github.com/repos/${GITHUB_OWNER}/${DIST_REPO}/contents/${path}"

    # Retried, because the APK is ~35 MB and base64 makes that a ~47 MB request
    # body. GitHub answers those with a 502 often enough to have killed a
    # release on a build that was otherwise finished and signed — and the cost
    # of that failure is the whole run, since the manifest never goes up and
    # the version bump is left uncommitted. The second attempt has always been
    # enough so far; three is for the day it is not.
    local response=""
    local attempt
    for attempt in 1 2 3; do
        # Keep transport failures inside the retry loop. With `set -e`, a
        # failed command substitution would otherwise terminate the whole
        # release before the retry code below can run.
        if ! response=$(curl --http1.1 -sS -o /dev/null -w "%{http_code}" \
            -X PUT \
            -H "Authorization: Bearer $GITHUB_TOKEN" \
            -H "Accept: application/vnd.github+json" \
            -H "Content-Type: application/json" \
            --data-binary "@${payload_file}" \
            "$url"); then
            response="transport-error"
        fi

        if [[ "$response" == "200" || "$response" == "201" ]]; then
            rm -f "$payload_file"
            return 0
        fi

        # A large PUT can reach GitHub successfully and lose only its response
        # on the way back (HTTP/2 CANCEL was observed in production). Confirm
        # the blob before retrying; otherwise the retry sends a now-stale SHA,
        # gets 409, and reports a failed release that actually published.
        local published_sha
        published_sha=$(api_get_sha "$path")
        if [[ "$published_sha" == "$source_blob_sha" ]]; then
            echo "   upload of ${path} completed despite the lost HTTP response"
            rm -f "$payload_file"
            return 0
        fi

        # 409 and 422 mean the SHA we sent is stale — something else wrote to
        # the file between the GET and the PUT. Retrying the same body would
        # fail the same way, so that one is fatal.
        if [[ "$response" == "409" || "$response" == "422" ]]; then
            break
        fi

        echo "   upload of ${path} got HTTP ${response}, attempt ${attempt} of 3" >&2
        sleep $((attempt * 5))
    done

    rm -f "$payload_file"
    echo "GitHub API upload of ${path} failed with HTTP ${response}" >&2
    return 1
}

if [[ $PUSH -eq 1 ]]; then
    # By git push, not the Contents API: past ~48 MB the API refuses the
    # base64 body with 422. See scripts/publish-dist.sh.
    echo ">> Publishing launcher.apk and latest.json to ${GITHUB_OWNER}/${DIST_REPO}"
    GITHUB_TOKEN="$GITHUB_TOKEN" GITHUB_OWNER="$GITHUB_OWNER" DIST_REPO="$DIST_REPO" APK_NAME="launcher.apk" \
        "$(dirname "$0")/publish-dist.sh" "$APK_OUT" "$MANIFEST_TMP" "Release ${NEW_NAME} (build ${NEW_CODE})"
else
    echo ">> Skipping distribution upload (--no-push)"
fi

rm -f "$MANIFEST_TMP"

# ---------------------------------------- commit the version bump in source --
echo ">> Committing version bump"
git add "$GRADLE_FILE"

if [[ $PUSH -eq 1 ]]; then
    RELEASE_STATUS="APK and latest.json were pushed to the public ${DIST_REPO} distribution repo."
else
    RELEASE_STATUS="Distribution upload was intentionally skipped with --no-push."
fi
COMMIT_MSG=$(cat <<EOF
Release ${NEW_NAME} (build ${NEW_CODE})

${NOTES}

Bumps versionCode to ${NEW_CODE}. ${RELEASE_STATUS}
Nothing is stored in the source tree.
EOF
)

git commit -m "$COMMIT_MSG"

if [[ $PUSH -eq 1 ]]; then
    echo ">> Pushing source to origin/main"
    git push origin main
    echo
    echo "Release ${NEW_NAME} published. In-app updater on ${CURRENT_NAME} will pick it up on the next check."
else
    echo
    echo "Release ${NEW_NAME} committed locally. Run 'git push origin main' when ready."
fi
