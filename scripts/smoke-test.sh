#!/usr/bin/env bash
# Smoke test do Execution no cluster (spec 11 §2): sobe um port-forward do Service e confere a saude da aplicacao.
# Requer KUBECONFIG apontando para o cluster. Nada secreto e impresso.
set -euo pipefail

port="${LOCAL_PORT:-18080}"
base="http://localhost:${port}"

kubectl -n oficina port-forward svc/oficina-execution "${port}:80" > /tmp/billing-port-forward.log 2>&1 &
pf_pid=$!
trap 'kill "$pf_pid" 2>/dev/null || true' EXIT

for _ in $(seq 1 20); do
  curl -sf "${base}/q/health/live" > /dev/null && break
  sleep 2
done

for probe in live ready; do
  if ! curl -sf "${base}/q/health/${probe}" > /dev/null; then
    echo "::error::/q/health/${probe} nao respondeu OK"
    curl -s "${base}/q/health/${probe}" || true
    exit 1
  fi
  echo "/q/health/${probe}: OK"
done

# Sem credencial, uma rota protegida precisa recusar (401): prova que a autenticacao esta ligada.
code="$(curl -s -o /dev/null -w '%{http_code}' "${base}/admin/executions")"
if [ "$code" != "401" ] && [ "$code" != "403" ]; then
  echo "::error::/admin/executions sem token devolveu ${code} (esperado 401/403)"
  exit 1
fi
echo "/admin/executions sem token: ${code} (protegida)"
