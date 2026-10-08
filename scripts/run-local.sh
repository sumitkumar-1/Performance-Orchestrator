#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
usage() {
  cat <<'HELP'
Usage: ./scripts/run-local.sh [build|run|build-run] [-- Spring Boot arguments...]
  build       Run Maven verify (including tests) and package the application.
  run         Run the existing package (default); fail if it is missing.
  build-run   Build, test, then run locally.
JAVA_HOME selects the JDK. Requires Java 21+; build also requires Maven.
Examples:
  ./scripts/run-local.sh build-run
  ./scripts/run-local.sh run
  ./scripts/run-local.sh run -- --server.port=8081
  ./scripts/run-local.sh --server.port=8081  # backwards compatible
HELP
}
action=run
case "${1:-}" in
  build|run|build-run) action=$1; shift ;;
  -h|--help) usage; exit 0 ;;
  --*|'') ;;
  *) usage >&2; exit 2 ;;
esac
if [[ ${1:-} == --mode ]]; then
  echo 'The --mode option has been removed. Use build-run or run; all integrations use configured services.' >&2
  exit 2
fi
[[ ${1:-} != -- ]] || shift
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v java >/dev/null || { echo 'Java is required; set JAVA_HOME to a JDK 21+ installation.' >&2; exit 1; }
version=$(java -version 2>&1)
if [[ $version =~ version\ \"([0-9]+) ]]; then
  (( BASH_REMATCH[1] >= 21 )) || { echo 'Java 21+ is required; set JAVA_HOME.' >&2; exit 1; }
else
  echo 'Cannot determine Java version.' >&2; exit 1
fi
if [[ $action == build && $# != 0 ]]; then echo 'build does not accept application arguments.' >&2; exit 2; fi
if [[ $action != run ]]; then
  command -v mvn >/dev/null || { echo 'Maven is required to build.' >&2; exit 1; }
  mvn -B clean verify
fi
[[ $action != build ]] || exit 0
jar=target/perf-orchestrator-0.1.0.jar
[[ -f $jar ]] || { echo 'Package missing. Run ./scripts/run-local.sh build-run.' >&2; exit 1; }
exec java -jar "$jar" "$@"
