#!/usr/bin/env bash
# Prints the build stamps as shell assignments (SETUP-11):
#   BUILD_VERSION  X.Y.Z when building a release tag vX.Y.Z, otherwise <VERSION>-dev+<commit>
#   GIT_COMMIT     short git commit, 7 characters
#   BUILD_TIME     UTC, ISO-8601
# Use:  eval "$(scripts/build-stamp.sh)" && docker compose --profile dev up -d --build
# A release tag must equal the VERSION file, so a tag can never build a different number than the one released.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(tr -d '[:space:]' < "$root/VERSION")"
commit="$(git -C "$root" rev-parse --short=7 HEAD 2>/dev/null || echo unknown)"

tag="${GITHUB_REF_NAME:-}"
if [[ "${GITHUB_REF:-}" != refs/tags/* ]]; then
  tag="$(git -C "$root" describe --tags --exact-match --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true)"
fi

if [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  build="${BASH_REMATCH[1]}"
  if [[ "$build" != "$version" ]]; then
    echo "Tag $tag does not match the VERSION file ($version). Update VERSION and CHANGELOG.md in the release PR." >&2
    exit 1
  fi
else
  build="$version-dev+$commit"
fi

echo "BUILD_VERSION=$build"
echo "GIT_COMMIT=$commit"
echo "BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
