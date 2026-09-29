#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
chart="$root/ckp/helm/ps-spoolers-perf-orchestrator"
usage() {
  cat <<'HELP'
Usage: ./scripts/deploy-ckp.sh [render|deploy] --namespace NAME --values FILE [options]
  render                  Lint and render locally (default; no cluster requests).
  deploy                  Lint, then helm upgrade --install --wait.
  --context NAME          Explicit kube-context; required for deploy.
  --namespace NAME        Existing CKP namespace; required.
  --release NAME          Default: ps-spoolers-perf-orchestrator
  --values FILE           Repeatable; no credentials in values files.
  --timeout DURATION      Default: 5m
Build/push an image and set image.repository and image.tag in your values file first.
This deploys the orchestrator app; it does not deploy test services or launch load.
HELP
}
action=render; context=''; namespace=''; release=ps-spoolers-perf-orchestrator; timeout=5m
values=()
case ${1:-} in render|deploy) action=$1; shift;; esac
while (( $# )); do
  case $1 in
    -h|--help) usage; exit 0;;
    --context|--namespace|--release|--values|--timeout)
      [[ $# -ge 2 && -n $2 && $2 != --* ]] || { echo "Missing value for $1" >&2; exit 2; }
      case $1 in
        --context) context=$2;; --namespace) namespace=$2;; --release) release=$2;;
        --timeout) timeout=$2;;
        --values) [[ -f $2 ]] || { echo 'Values file does not exist.' >&2; exit 2; }; values+=(--values "$2");;
      esac
      shift 2;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2;;
  esac
done
[[ -n $namespace && ${#values[@]} -gt 0 ]] || { usage >&2; exit 2; }
[[ $namespace =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ && ${#namespace} -le 63 ]] || { echo 'Invalid namespace.' >&2; exit 2; }
[[ $release =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ && ${#release} -le 53 ]] || { echo 'Invalid release name.' >&2; exit 2; }
[[ $action != deploy || -n $context ]] || { echo '--context is required for deploy.' >&2; exit 2; }
command -v helm >/dev/null || { echo 'Helm 3 is required.' >&2; exit 1; }
helm lint "$chart" "${values[@]}" >&2
if [[ $action == render ]]; then
  exec helm template "$release" "$chart" --namespace "$namespace" "${values[@]}"
fi
exec helm upgrade --install "$release" "$chart" --namespace "$namespace" \
  --kube-context "$context" "${values[@]}" --wait --timeout "$timeout" --history-max 10
