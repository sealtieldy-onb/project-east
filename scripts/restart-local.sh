#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
project_root="$(cd -- "$script_dir/.." && pwd -P)"

if [[ ! -d "$project_root/frontend" || ! -d "$project_root/backend" ]]; then
  echo "Run this script from a Project East checkout." >&2
  exit 1
fi

for utility in lsof npm mvn; do
  if ! command -v "$utility" >/dev/null 2>&1; then
    echo "Required command not found: $utility" >&2
    exit 1
  fi
done

if [[ -n "${EAST_SERVER_PORT:-}" ]]; then
  backend_port="$EAST_SERVER_PORT"
else
  backend_port="$(sed -nE \
    -e 's/^[[:space:]]*EAST_SERVER_PORT[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*(#.*)?$/\1/p' \
    -e 's/^[[:space:]]*EAST_SERVER_PORT[[:space:]]*=[[:space:]]*"([0-9]+)"[[:space:]]*(#.*)?$/\1/p' \
    -e "s/^[[:space:]]*EAST_SERVER_PORT[[:space:]]*=[[:space:]]*'([0-9]+)'[[:space:]]*(#.*)?$/\\1/p" \
    "$project_root/.env" 2>/dev/null | head -n 1 || true)"
  backend_port="${backend_port:-8080}"
fi

if [[ ! "$backend_port" =~ ^[0-9]{1,5}$ ]] || (( 10#$backend_port < 1 || 10#$backend_port > 65535 )); then
  echo "EAST_SERVER_PORT must be a number from 1 to 65535." >&2
  exit 1
fi

if [[ ! -x "$project_root/frontend/node_modules/.bin/vite" ]]; then
  echo "Frontend dependencies are missing. Run 'npm install' in frontend/ first." >&2
  exit 1
fi

umask 077
temp_dir="${TMPDIR:-/tmp}"
temp_dir="${temp_dir%/}"
run_dir="$temp_dir/east-project"
mkdir -p "$run_dir"
chmod 700 "$run_dir"
backend_log="$run_dir/backend.log"
frontend_log="$run_dir/frontend.log"
: > "$backend_log"
: > "$frontend_log"

process_cwd() {
  lsof -a -p "$1" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -n 1
}

process_name() {
  lsof -a -p "$1" -d cwd -Fc 2>/dev/null | sed -n 's/^c//p' | head -n 1
}

listener_pids() {
  lsof -nP -t -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | sort -un
}

project_listener_pids() {
  local area="$1"
  local port="$2"
  local expected_name="$3"
  local pid cwd name

  while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    cwd="$(process_cwd "$pid")"
    case "$cwd" in
      "$project_root/$area"|"$project_root/$area"/*) ;;
      *) continue ;;
    esac
    name="$(process_name "$pid")"
    [[ "$name" == "$expected_name" ]] || continue
    printf '%s\n' "$pid"
  done < <(listener_pids "$port")
}

stop_project_service() {
  local area="$1"
  local label="$2"
  local port="$3"
  local expected_name="$4"
  local pid attempt
  local pids=()
  local remaining=()
  local other_pids=()

  while IFS= read -r pid; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    pids+=("$pid")
  done < <(project_listener_pids "$area" "$port" "$expected_name")

  if (( ${#pids[@]} == 0 )); then
    while IFS= read -r pid; do
      [[ "$pid" =~ ^[0-9]+$ ]] || continue
      other_pids+=("$pid")
    done < <(listener_pids "$port")
    if (( ${#other_pids[@]} > 0 )); then
      echo "Port $port has a listener outside this checkout; leaving it untouched." >&2
    else
      echo "No running $label listener found in this checkout."
    fi
    return
  fi

  echo "Stopping $label process(es) on port $port: ${pids[*]}"
  for pid in "${pids[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  for attempt in {1..25}; do
    remaining=()
    while IFS= read -r pid; do
      [[ "$pid" =~ ^[0-9]+$ ]] || continue
      remaining+=("$pid")
    done < <(project_listener_pids "$area" "$port" "$expected_name")
    (( ${#remaining[@]} == 0 )) && return
    sleep 0.2
  done

  for pid in "${remaining[@]}"; do
    echo "Force-stopping unresponsive $label process $pid." >&2
    kill -KILL "$pid" 2>/dev/null || true
  done
}

wait_for_services() {
  local attempt
  local backend_ready=false
  local frontend_ready=false

  for attempt in {1..90}; do
    if [[ "$backend_ready" == false ]] && project_listener_pids backend "$backend_port" java | grep -q .; then
      backend_ready=true
      echo "Spring Boot is listening on 127.0.0.1:$backend_port."
    fi
    if [[ "$frontend_ready" == false ]] && project_listener_pids frontend 5173 node | grep -q .; then
      frontend_ready=true
      echo "Vite is listening on 127.0.0.1:5173."
    fi
    [[ "$backend_ready" == true && "$frontend_ready" == true ]] && return 0
    sleep 0.5
  done

  if [[ "$backend_ready" == false ]]; then
    echo "Spring Boot did not become ready on port $backend_port. Recent log:" >&2
    tail -n 40 "$backend_log" >&2 || true
  fi
  if [[ "$frontend_ready" == false ]]; then
    echo "Vite did not become ready on port 5173. Recent log:" >&2
    tail -n 40 "$frontend_log" >&2 || true
  fi
  return 1
}

stop_project_service backend "Spring Boot" "$backend_port" java
stop_project_service frontend "Vite" 5173 node

backend_redirect_uri="http://localhost:${backend_port}/login/oauth2/code/google"

(
  cd "$project_root/backend"
  nohup env \
    EAST_SERVER_PORT="$backend_port" \
    EAST_GOOGLE_REDIRECT_URI="$backend_redirect_uri" \
    mvn -B spring-boot:run \
    >"$backend_log" 2>&1 </dev/null &
  echo "Starting Spring Boot (port $backend_port, launcher PID $!). Log: $backend_log"
)

(
  cd "$project_root/frontend"
  nohup env \
    EAST_SERVER_PORT="$backend_port" \
    EAST_API_TARGET="http://127.0.0.1:${backend_port}" \
    npm run dev -- --strictPort \
    >"$frontend_log" 2>&1 </dev/null &
  echo "Starting Vite (http://localhost:5173, launcher PID $!). Log: $frontend_log"
)

if ! wait_for_services; then
  exit 1
fi

echo "East restart complete. PostgreSQL was left running."
