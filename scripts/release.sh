#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage:"
  echo "  $0 verify --upstream-tag vX.Y.Z [--suffix log] [--dry-run] [--allow-dirty]"
  echo "  $0 prod   --upstream-tag vX.Y.Z [--suffix log] [--dry-run] [--allow-dirty]"
  echo "  $0 dev [--dry-run] [--allow-dirty]"
  echo "  $0 publish --tags tag1,tag2 [--platform linux/amd64] [--dry-run] [--allow-dirty]"
}

die() { echo "error: $*" >&2; exit 1; }
run() { [[ "$DRY_RUN" == "1" ]] && echo "[dry-run] $*" || "$@"; }

require_clean_tree() {
  [[ "$ALLOW_DIRTY" == "1" ]] && return 0
  [[ -z "$(git status --porcelain)" ]] || die "working tree not clean (use --allow-dirty to skip)"
}
ensure_fork_tag() {
  local fork_tag="$1" msg="$2"
  local head_sha tag_sha remote_sha
  head_sha="$(git rev-parse HEAD)"
  FORK_TAG_FORCE_PUSH="0"
  if git rev-parse "$fork_tag" >/dev/null 2>&1; then
    tag_sha="$(git rev-parse "${fork_tag}^{commit}")"
    if [[ "$tag_sha" == "$head_sha" ]]; then
      echo "reusing tag ${fork_tag} at HEAD"
      return 0
    fi
    echo "moving tag ${fork_tag} from ${tag_sha} to ${head_sha}"
    run git tag -d "$fork_tag"
    FORK_TAG_FORCE_PUSH="1"
  else
    remote_sha="$(git ls-remote --tags origin "refs/tags/${fork_tag}^{}" | awk '{print $1}')"
    [[ -n "$remote_sha" ]] || remote_sha="$(git ls-remote --tags origin "refs/tags/${fork_tag}" | awk '{print $1}')"
    if [[ -n "$remote_sha" && "$remote_sha" != "$head_sha" ]]; then
      echo "remote tag ${fork_tag} differs from HEAD; will force-push after recreate"
      FORK_TAG_FORCE_PUSH="1"
    fi
  fi
  run git tag -a "$fork_tag" -m "$msg"
}

push_fork_tag() {
  local fork_tag="$1"
  if [[ "$FORK_TAG_FORCE_PUSH" == "1" ]]; then
    run git push origin "$fork_tag" --force
  else
    run git push origin "$fork_tag"
  fi
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

docker_ok() { docker info >/dev/null 2>&1; }

with_docker() {
  if docker_ok; then
    "$@"
  elif command -v sg >/dev/null 2>&1; then
    sg docker -c "$(printf '%q ' "$@")"
  else
    die "docker not accessible (add user to docker group, then log out/in or run: newgrp docker)"
  fi
}

prepare_auth() {
  local token=""
  if command -v gh >/dev/null 2>&1 && gh auth status -h github.com >/dev/null 2>&1; then
    if gh auth status 2>&1 | grep -q 'write:packages'; then
      token="$(gh auth token)"
    fi
  fi
  if [[ -z "$token" && -n "${GITHUB_TOKEN:-}" ]]; then
    token="$GITHUB_TOKEN"
  fi
  [[ -n "$token" ]] || die "need GHCR auth: gh auth refresh -h github.com -s write:packages (or set GITHUB_TOKEN)"
  export DOCKERHUB_USERNAME="${GITHUB_ACTOR:-$(fork_owner)}"
  export DOCKERHUB_TOKEN="$token"
}

ensure_foundation_resources() {
  run git submodule update --init --recursive foundation/src/main/resources/public
  if [[ "$DRY_RUN" != "1" ]]; then
    [[ -f foundation/src/main/resources/public/chains.yaml ]] || die "missing foundation resource chains.yaml after submodule init"
  fi
}

ensure_local_docker() {
  local build_args=(-t drpc-dshackle .)
  [[ -n "$PLATFORM" ]] && build_args=(--platform "$PLATFORM" "${build_args[@]}")
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] docker build ${build_args[*]}"
    return 0
  fi
  if [[ -n "$PLATFORM" ]]; then
    with_docker docker build "${build_args[@]}"
    return 0
  fi
  docker image inspect drpc-dshackle >/dev/null 2>&1 || with_docker docker build "${build_args[@]}"
}

publish_ghcr() {
  local tags="$1" owner
  owner="$(fork_owner)"
  prepare_auth
  ensure_foundation_resources
  ensure_local_docker
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "[dry-run] ./gradlew --no-daemon jib -Pdocker=ghcr.io/${owner} -Djib.to.tags=${tags}"
    return 0
  fi
  ./gradlew --stop >/dev/null 2>&1 || true
  with_docker ./gradlew --no-daemon jib -Pdocker="ghcr.io/${owner}" -Djib.to.tags="${tags}"
}

MODE="${1:-}"; [[ -n "$MODE" ]] || { usage; exit 1; }; shift || true
UPSTREAM_TAG=""; SUFFIX="log"; DRY_RUN="0"; ALLOW_DIRTY="0"; PLATFORM=""; PUBLISH_TAGS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --upstream-tag) UPSTREAM_TAG="${2:-}"; shift 2 ;;
    --suffix) SUFFIX="${2:-}"; shift 2 ;;
    --platform) PLATFORM="${2:-}"; shift 2 ;;
    --tags) PUBLISH_TAGS="${2:-}"; shift 2 ;;
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
    push_fork_tag "$FORK_TAG"
    run git checkout "$FORK_TAG"
    publish_ghcr "${FORK_TAG#v},${SHA}"
    run git checkout -
    ;;
  dev)
    SNAPSHOT="$(next_snapshot)"; SHA="$(git rev-parse --short HEAD)"
    TS="t$(date -u +%Y%m%d%H%M)"
    publish_ghcr "${SNAPSHOT},${TS},${SHA}"
    ;;
  publish)
    [[ -n "$PUBLISH_TAGS" ]] || die "publish requires --tags"
    publish_ghcr "$PUBLISH_TAGS"
    ;;
  *) usage; die "unknown mode: $MODE" ;;
esac
