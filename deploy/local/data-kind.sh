#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
cluster=${DATA_KIND_CLUSTER:-cartyx-data-test}
mkdir -p .local/data
chmod 700 .local/data
kubeconfig="$PWD/.local/data/$cluster.kubeconfig"
if ! kind get clusters | grep -qx "$cluster"; then
  kind create cluster --name "$cluster" --kubeconfig "$kubeconfig" --wait 120s
else
  kind get kubeconfig --name "$cluster" > "$kubeconfig"
fi
chmod 600 "$kubeconfig"
kubectl --kubeconfig "$kubeconfig" create namespace cartyx-local --dry-run=client -o yaml |
  kubectl --kubeconfig "$kubeconfig" apply -f -
DATA_KUBECONFIG="$kubeconfig" node deploy/data/kubernetes.mjs provision-secret local
helm --kubeconfig "$kubeconfig" upgrade --install cartyx-data deploy/charts/cartyx-data \
  --namespace cartyx-local -f deploy/charts/cartyx-data/values-local.yaml \
  --wait --wait-for-jobs --timeout 15m
echo "Data release ready. Kubeconfig: $kubeconfig"
