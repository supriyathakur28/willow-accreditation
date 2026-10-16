#!/usr/bin/env bash
# Starts the accreditation service and its infrastructure on http://localhost:9999
#
#   ./run.sh          build and start everything, wait until healthy
#   ./run.sh infra    start only MySQL, Kafka and Redis, to run the service from an IDE
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
  infra)
    docker compose stop app 2>/dev/null || true     # frees 9999/9998 if the full stack was running
    docker compose up --detach --wait mysql redis kafka
    # Topics and sample users must exist before the service starts: a consumer that subscribes to a missing
    # topic only notices it minutes later
    docker compose up --exit-code-from kafka-init kafka-init
    echo
    echo "Infrastructure is up. Run or debug Application from your IDE; it connects to:"
    echo "  MySQL  localhost:3306    Kafka  localhost:29092    Redis  localhost:6379"
    echo "The service will be on http://localhost:9999 (health: http://localhost:9998/health)."
    ;;
  down)  docker compose down ;;
  clean) docker compose down --volumes ;;
  logs)  docker compose logs --follow app ;;
  *)     echo "Usage: $0 [up|infra|down|clean|logs]" >&2; exit 1 ;;
esac
