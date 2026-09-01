#!/bin/bash
set -e

DB_HOST="${DB_HOST:-db}"
DB_PORT="${DB_PORT:-3306}"
MAX_RETRIES=30
RETRY_INTERVAL=2

echo "Waiting for MySQL at ${DB_HOST}:${DB_PORT}..."

for i in $(seq 1 $MAX_RETRIES); do
  (echo > /dev/tcp/"$DB_HOST"/"$DB_PORT") 2>/dev/null && { echo "MySQL is ready!"; break; }
  echo "Attempt $i/$MAX_RETRIES — MySQL not ready, waiting ${RETRY_INTERVAL}s..."
  sleep $RETRY_INTERVAL
done

exec java -jar /app/app.jar
