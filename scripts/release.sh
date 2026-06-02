#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage:"
  echo "  $0 verify --upstream-tag vX.Y.Z [--suffix log] [--dry-run] [--allow-dirty]"
  echo "  $0 prod   --upstream-tag vX.Y.Z [--suffix log] [--dry-run] [--allow-dirty]"
  echo "  $0 dev [--dry-run] [--allow-dirty]"
}

die() { echo "error: $*" >&2; exit 1; }
run() { [[ "$DRY_RUN" == "1" ]] && echo "[dry-run] $*" || "$@"; }

require_clean_tree() {
  [[ "$ALLOW_DIRTY" == "1" ]] && return 0
  [[ -z "$(git status --porcelain)" ]] || die "working tree not clean (use --allow-dirty to skip)"
}
ensure_fork_tag() {
  local fork_tag="$1" msg="$2"
  local head_sha tag_sha
  head_sha="$(git rev-parse HEAD)"
  if git rev-parse "$fork_tag" >/dev/null 2>&1; then
    tag_sha="$(git rev-parse "${fork_tag}^{commit}")"
    [[ "$tag_sha" == "$head_sha" ]] || die "tag ${fork_tag} exists at ${tag_sha}, not current HEAD ${head_sha}"
    echo "reusing tag ${fork_tag} at HEAD"
    return 0
  fi
  run git tag -a "$fork_tag" -m "$msg"
}

fork_owner() { git remote get-url origin | sed -E 's#.*github.com[:/]([^/]+)/.*#\1#'; }

verify_upstream() {
  [[ "$UPSTREAM_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid upstream tag: $UPSTREAM_TAG"
  git remote get-url upstream >/dev/null 2>&1 || die "missing upstream remote"
  run git fetch upstream --tags --force
  UPSTREAM_SHA="$(git rev-parse "${UPSTREAM_TAG}^{commit}" 2>/dev/null || true)"
  [[ -n "$UPSTREAM_SHA" ]] || die "cannot resolve local tag commit: $UPSTREAM_TAG"
  REMOTE_SHA="$(git ls-remote --tags upstream "refs/tags/${UPSTREAM_TAG}^{}" | awk '{print $1}')"
  [[ -n "$REMOTE_SHA" ]] || REMOTE_SHA="$(git ls-remote --tags upstream "refs/tags/${UPSTREAM_TAG}" | awk '{print $1}')"
  [[ -n "$REMOTE_SHA" && "$UPSTREAM_SHA" == "$REMOTE_SHA" ]] || die "upstream tag not verified: $UPSTREAM_TAG"
  git merge-base --is-ancestor "$UPSTREAM_SHA" HEAD || die "HEAD is not based on $UPSTREAM_TAG"
}

next_snapshot() {
  local last patch
  last="$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' | sort -V | tail -1)"
  [[ -n "$last" ]] || die "no semver tags found"
  patch="${last##*.}"
  echo "${last#v}" | sed -E "s/[0-9]+$/$((patch + 1))/" | sed -E 's/$/-SNAPSHOT/'
}

prepare_auth() {
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    export DOCKERHUB_USERNAME="${GITHUB_ACTOR:-$(fork_owner)}"
    export DOCKERHUB_TOKEN="$GITHUB_TOKEN"
  fi
}

ensure_foundation_resources() {
  run git submodule update --init --recursive foundation/src/main/resources/public
  if [[ "$DRY_RUN" != "1" ]]; then
    [[ -f foundation/src/main/resources/public/chains.yaml ]] || die "missing foundation resource chains.yaml after submodule init"
  fi
}

publish_ghcr() {
  local tags="$1" owner
  owner="$(fork_owner)"
  prepare_auth
  ensure_foundation_resources
  run ./gradlew jib -Pdocker="ghcr.io/${owner}" -Djib.to.tags="${tags}"
}

MODE="${1:-}"; [[ -n "$MODE" ]] || { usage; exit 1; }; shift || true
UPSTREAM_TAG=""; SUFFIX="log"; DRY_RUN="0"; ALLOW_DIRTY="0"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --upstream-tag) UPSTREAM_TAG="${2:-}"; shift 2 ;;
    --suffix) SUFFIX="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN="1"; shift ;;
    --allow-dirty) ALLOW_DIRTY="1"; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_clean_tree
case "$MODE" in
  verify)
    [[ -n "$UPSTREAM_TAG" ]] || die "verify requires --upstream-tag"
    verify_upstream
    echo "verified: ${UPSTREAM_TAG} -> ${UPSTREAM_TAG}-${SUFFIX}"
    ;;
  prod)
    [[ -n "$UPSTREAM_TAG" ]] || die "prod requires --upstream-tag"
    verify_upstream
    FORK_TAG="${UPSTREAM_TAG}-${SUFFIX}"
    [[ "$SUFFIX" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid suffix: $SUFFIX"
    OWNER="$(fork_owner)"; SHA="$(git rev-parse --short HEAD)"
    MSG="upstream: drpcorg/dshackle@${UPSTREAM_TAG} (${UPSTREAM_SHA})
fork: ${FORK_TAG} @ $(git rev-parse HEAD)
images: ghcr.io/${OWNER}/dshackle:${FORK_TAG#v}, ghcr.io/${OWNER}/dshackle:${SHA}
fork commits since upstream:
$(git log --oneline "${UPSTREAM_TAG}..HEAD")"
    ensure_fork_tag "$FORK_TAG" "$MSG"
    run git push origin "$FORK_TAG"
    run git checkout "$FORK_TAG"
    publish_ghcr "${FORK_TAG#v},${SHA}"
    ;;
  dev)
    SNAPSHOT="$(next_snapshot)"; SHA="$(git rev-parse --short HEAD)"
    TS="t$(date -u +%Y%m%d%H%M)"
    publish_ghcr "${SNAPSHOT},${TS},${SHA}"
    ;;
  *) usage; die "unknown mode: $MODE" ;;
esac
