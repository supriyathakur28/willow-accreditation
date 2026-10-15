#!/usr/bin/env bash
# Starts the accreditation service and its infrastructure on http://localhost:9999
#
#   ./run.sh          build and start everything, wait until healthy
#   ./run.sh down     stop the stack (data is kept)
#   ./run.sh clean    stop the stack and delete its data
#   ./run.sh logs     follow the service logs
#
# Security is off by default; start it with:  SECURITY_ENABLED=true ./run.sh
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "Docker is required: https://docs.docker.com/get-docker/" >&2; exit 1; }

case "${1:-up}" in
  up)
    docker compose up --build --detach --wait
    echo
    echo "Accreditation service is up:"
    echo "  API         http://localhost:9999"
    echo "  Swagger UI  http://localhost:9999/swagger-ui/index.html"
    echo "  Health      http://localhost:9998/health"
    echo "  Metrics     http://localhost:9998/prometheus"
    ;;
  down)  docker compose down ;;
  clean) docker compose down --volumes ;;
  logs)  docker compose logs --follow app ;;
  *)     echo "Usage: $0 [up|down|clean|logs]" >&2; exit 1 ;;
esac
