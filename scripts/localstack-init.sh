#!/usr/bin/env bash
# Filas FIFO do ambiente local (docker compose). Em AWS elas vêm do Terraform (oficina-infra-k8s, spec 12).
set -euo pipefail

for queue in oficina-local-execution-commands oficina-local-os-saga-replies; do
  awslocal sqs create-queue --queue-name "${queue}.fifo" --attributes FifoQueue=true >/dev/null
  echo "fila ${queue}.fifo pronta"
done
