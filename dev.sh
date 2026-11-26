#!/usr/bin/env bash
#
# One-command development start: the backend with hot reload (sbt "~szorkStart") and the frontend dev server.
#
#   ./dev.sh            start both, wait until they answer, print the URL to open
#   Ctrl+C              stop both
#
# Needs Java 21+, sbt, Node 22+ and a .env (copy .env.example). Runs `npm ci` when frontend/node_modules is
# missing. Refuses to start when a port is taken; it never kills anything it did not start.
# Logs: backend.log and frontend.log in the repository root. SZORK_DEV_TIMEOUT (seconds, default 600) is how long
# to wait for a cold sbt start.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

BACKEND_PORT=8090
WEBSOCKET_PORT=9002
FRONTEND_PORT=3090
FRONTEND_URL="http://localhost:${FRONTEND_PORT}"
TIMEOUT="${SZORK_DEV_TIMEOUT:-600}"

if [ -t 1 ]; then
    RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[1;33m'; NC=$'\033[0m'
else
    RED=""; GREEN=""; YELLOW=""; NC=""
fi
say() { printf '%s\n' "${GREEN}$*${NC}"; }
warn() { printf '%s\n' "${YELLOW}$*${NC}"; }
die() { printf '%s\n' "${RED}ERROR: $*${NC}" >&2; exit 1; }

BACKEND_PID=""
FRONTEND_PID=""

# ---- prerequisites -------------------------------------------------------------------------------------------

command -v java > /dev/null 2>&1 || die "java not found. Install JDK 21 or newer."
java_major=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')
[ "${java_major:-0}" -ge 21 ] 2> /dev/null || die "Java 21 or newer is required (found ${java_major:-unknown})."

command -v sbt > /dev/null 2>&1 || die "sbt not found. Install sbt 1.12 or newer (https://www.scala-sbt.org/download)."

command -v node > /dev/null 2>&1 || die "node not found. Install Node 22 or newer (see .nvmrc)."
node_major=$(node -p 'process.versions.node.split(".")[0]')
[ "$node_major" -ge 22 ] || die "Node 22 or newer is required (found $(node -v)). See .nvmrc."
command -v npm > /dev/null 2>&1 || die "npm not found (it comes with Node)."
command -v curl > /dev/null 2>&1 || die "curl not found."

[ -f .env ] || die ".env not found. Copy .env.example to .env and add your API keys: cp .env.example .env"

# ---- ports: refuse to start on a taken port ------------------------------------------------------------------

port_listeners() {
    if command -v lsof > /dev/null 2>&1; then
        lsof -ti "tcp:$1" -sTCP:LISTEN 2> /dev/null || true
    fi
}

for port in "$BACKEND_PORT" "$WEBSOCKET_PORT" "$FRONTEND_PORT"; do
    pids=$(port_listeners "$port" | tr '\n' ' ')
    if [ -n "${pids// /}" ]; then
        die "port $port is already in use (PID ${pids% }). Stop that process (kill ${pids% }), or run 'sbt szorkStop' if it is an earlier Szork."
    fi
done

# ---- cleanup -------------------------------------------------------------------------------------------------

# Stops a process and everything it started
kill_tree() {
    local pid=$1 child
    for child in $(pgrep -P "$pid" 2> /dev/null || true); do
        kill_tree "$child"
    done
    kill -TERM "$pid" 2> /dev/null || true
}

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    warn "Shutting down the development servers..."
    if [ -n "$FRONTEND_PID" ]; then
        kill_tree "$FRONTEND_PID"
        wait "$FRONTEND_PID" 2> /dev/null || true
    fi
    if [ -n "$BACKEND_PID" ]; then
        kill_tree "$BACKEND_PID"
        wait "$BACKEND_PID" 2> /dev/null || true
    fi
    exit "$status"
}

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- frontend dependencies -----------------------------------------------------------------------------------

if [ ! -d frontend/node_modules ]; then
    warn "frontend/node_modules is missing: running npm ci..."
    (cd frontend && npm ci) || die "npm ci failed."
fi

# ---- start ---------------------------------------------------------------------------------------------------

say "=== Szork development environment ==="

warn "Starting the backend (hot reload; the first start may take minutes while sbt resolves)..."
sbt "~szorkStart" > backend.log 2>&1 &
BACKEND_PID=$!

waited=0
until curl -fs -o /dev/null "http://localhost:${BACKEND_PORT}/api/feature-flags"; do
    if ! kill -0 "$BACKEND_PID" 2> /dev/null; then
        tail -20 backend.log
        die "the backend exited. See backend.log."
    fi
    if [ "$waited" -ge "$TIMEOUT" ]; then
        tail -20 backend.log
        die "the backend did not answer on port ${BACKEND_PORT} within ${TIMEOUT}s. See backend.log."
    fi
    if [ $((waited % 10)) -eq 0 ] && [ "$waited" -gt 0 ]; then
        echo "  still waiting for the backend (${waited}s)..."
    fi
    sleep 2
    waited=$((waited + 2))
done
say "Backend is ready."

warn "Starting the frontend..."
(cd frontend && exec npm run dev) > frontend.log 2>&1 &
FRONTEND_PID=$!

waited=0
until curl -fs -o /dev/null "$FRONTEND_URL"; do
    if ! kill -0 "$FRONTEND_PID" 2> /dev/null; then
        tail -20 frontend.log
        die "the frontend exited. See frontend.log."
    fi
    if [ "$waited" -ge 120 ]; then
        tail -20 frontend.log
        die "the frontend did not answer on port ${FRONTEND_PORT} within 120s. See frontend.log."
    fi
    sleep 1
    waited=$((waited + 1))
done

echo ""
say "Open ${FRONTEND_URL}"
echo "  Backend:   http://localhost:${BACKEND_PORT}  (WebSocket ws://localhost:${WEBSOCKET_PORT})"
echo "  Logs:      tail -f backend.log frontend.log"
echo "  Ctrl+C stops both."
echo ""

# Run until either server dies or the script is interrupted
while kill -0 "$BACKEND_PID" 2> /dev/null && kill -0 "$FRONTEND_PID" 2> /dev/null; do
    sleep 2
done
if ! kill -0 "$BACKEND_PID" 2> /dev/null; then
    tail -20 backend.log
    die "the backend exited. See backend.log."
fi
tail -20 frontend.log
die "the frontend exited. See frontend.log."
