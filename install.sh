#!/usr/bin/env bash
# Installs the branch-scoped-local-repository Maven core extension into every Maven
# installation found on this machine: mvn on PATH, mvnd on PATH, and all Maven wrapper
# distributions. Re-run after upgrading Maven/mvnd or when the wrapper downloads a new
# distribution.
#
# Usage:
#   install.sh [version]     install the latest (or the given) version
#   install.sh --uninstall   remove the extension from all found locations
#   install.sh --dry-run     only show what would be done
set -euo pipefail

ARTIFACT="branch-scoped-local-repository"
BASE_URL="https://repo.maven.apache.org/maven2/io/github/lenaschoenburg/${ARTIFACT}"

DRY_RUN=false
UNINSTALL=false
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --uninstall) UNINSTALL=true ;;
    -h|--help)
      sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) VERSION="$arg" ;;
  esac
done

log() { printf '%s\n' "$*"; }

fetch() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$1"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO- "$1"
  else
    log "error: neither curl nor wget is available" >&2
    exit 1
  fi
}

# --- discover lib/ext directories of all Maven installations ---
TARGETS=""
add_maven_home() {
  # A Maven home (also mvnd's embedded one) always has both boot/ and lib/.
  local home="$1"
  [ -n "$home" ] && [ -d "$home/lib" ] && [ -d "$home/boot" ] || return 0
  case "$TARGETS" in *"$home/lib/ext"*) return 0 ;; esac
  TARGETS="${TARGETS}${home}/lib/ext
"
}

if command -v mvn >/dev/null 2>&1; then
  add_maven_home "$(mvn --version 2>/dev/null | sed -n 's/^Maven home: //p' | head -1)"
fi
if command -v mvnd >/dev/null 2>&1; then
  add_maven_home "$(mvnd --version 2>/dev/null | sed -n 's/^Maven home: //p' | head -1)"
fi
add_maven_home "${MAVEN_HOME:-}"
# Wrapper distributions come in two layouts: wrapper-jar and pre-3.3 scripts nest the
# distribution (dists/<name>/<hash>/apache-maven-x/), the 3.3+ only-script wrapper unpacks
# the home directly into the hash directory (dists/<name>/<hash>/). The mvnd wrapper nests
# the Maven home one level deeper under mvn/.
dists="${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists"
for dist in "$dists"/*/* "$dists"/*/*/apache-maven-* "$dists"/*/*/mvn "$dists"/*/*/maven-mvnd-*/mvn; do
  [ -d "$dist" ] && add_maven_home "$dist"
done

if [ -z "$TARGETS" ]; then
  log "No Maven installation found (checked: mvn and mvnd on PATH, MAVEN_HOME, wrapper dists)."
  exit 1
fi

# --- uninstall ---
if $UNINSTALL; then
  while IFS= read -r ext; do
    [ -n "$ext" ] || continue
    for jar in "$ext/$ARTIFACT"-*.jar; do
      [ -e "$jar" ] || continue
      if $DRY_RUN; then log "would remove $jar"; else rm -f "$jar" && log "removed $jar"; fi
    done
  done <<EOF
$TARGETS
EOF
  exit 0
fi

# --- resolve version and download once ---
if [ -z "$VERSION" ]; then
  VERSION=$(fetch "$BASE_URL/maven-metadata.xml" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)
  if [ -z "$VERSION" ]; then
    log "error: could not determine the latest version from Maven Central" >&2
    exit 1
  fi
fi
JAR="$ARTIFACT-$VERSION.jar"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
log "Downloading $JAR from Maven Central..."
fetch "$BASE_URL/$VERSION/$JAR" > "$TMP/$JAR"

expected=$(fetch "$BASE_URL/$VERSION/$JAR.sha1")
if command -v sha1sum >/dev/null 2>&1; then
  actual=$(sha1sum "$TMP/$JAR" | cut -d' ' -f1)
elif command -v shasum >/dev/null 2>&1; then
  actual=$(shasum "$TMP/$JAR" | cut -d' ' -f1)
else
  actual="$expected"
  log "warning: no sha1 tool found, skipping checksum verification"
fi
if [ "$actual" != "$expected" ]; then
  log "error: checksum mismatch for $JAR (expected $expected, got $actual)" >&2
  exit 1
fi

# --- install into every target, replacing older versions ---
while IFS= read -r ext; do
  [ -n "$ext" ] || continue
  if $DRY_RUN; then
    log "would install $JAR -> $ext/"
    continue
  fi
  if ! mkdir -p "$ext" 2>/dev/null || [ ! -w "$ext" ]; then
    log "skipped (not writable, re-run with sudo for this one): $ext"
    continue
  fi
  rm -f "$ext/$ARTIFACT"-*.jar
  cp "$TMP/$JAR" "$ext/"
  log "installed $JAR -> $ext/"
done <<EOF
$TARGETS
EOF

log ""
log "Done. Local installs are now scoped by git branch (~/.m2/repository/installed/<branch>/)."
log "Uninstall anytime with: $0 --uninstall"
