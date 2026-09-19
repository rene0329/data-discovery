#!/usr/bin/env bash
set -euo pipefail

# Fixed Master + A/B/C Lite RunK bootstrap based on Kuscia v1.2.0b0 hack/k8s.
# Domain keys and deploy tokens stay in a mode-0700 temporary directory.

readonly here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly master_domain="${KUSCIA_MASTER_DOMAIN_ID:-topic4-master}"
readonly rollout_timeout="${KUSCIA_ROLLOUT_TIMEOUT:-10m}"
: "${KUSCIA_MASTER_DATASTORE_ENDPOINT:?set the dedicated Master MySQL DSN}"
: "${NODE_A:?set NODE_A to the outer Kubernetes node name for domain A}"
: "${NODE_B:?set NODE_B to the outer Kubernetes node name for domain B}"
: "${NODE_C:?set NODE_C to the outer Kubernetes node name for domain C}"

case "$KUSCIA_MASTER_DATASTORE_ENDPOINT" in
    *$'\n'*|*'"'*) echo "KUSCIA_MASTER_DATASTORE_ENDPOINT contains unsupported YAML characters" >&2; exit 2 ;;
    mysql://*'@tcp('*')/'*) ;;
    *) echo "KUSCIA_MASTER_DATASTORE_ENDPOINT must be an explicit mysql://...@tcp(...)/... DSN" >&2; exit 2 ;;
esac
for node_name in "$NODE_A" "$NODE_B" "$NODE_C"; do
  [[ "$node_name" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$ ]] || {
    echo "NODE_A/B/C must be Kubernetes DNS node names" >&2
    exit 2
  }
done

work="$(mktemp -d "${TMPDIR:-/tmp}/topic4-kuscia-bootstrap.XXXXXX")"
cleanup() {
  if [[ -d "$work" ]]; then
    find "$work" -type f -exec sh -c 'for f do : > "$f"; done' sh {} +
    rmdir "$work" 2>/dev/null || true
  fi
}
trap cleanup EXIT
umask 077

domain_key() {
  # OpenSSL 3 needs -traditional for Kuscia's PKCS#1 domain key, while
  # OpenSSL 1.1 emits PKCS#1 by default and rejects that option.
  if openssl genrsa -help 2>&1 | grep -q -- '-traditional'; then
    openssl genrsa -traditional 2048 2>/dev/null
  else
    openssl genrsa 2048 2>/dev/null
  fi
}

write_master_config() {
  local key_file="$1" output="$2"
  local encoded_key
  encoded_key="$(base64 < "$key_file" | tr -d '\n')"
  {
    echo "mode: master"
    echo "domainID: ${master_domain}"
    printf 'domainKeyData: "%s"\n' "$encoded_key"
    echo "logLevel: INFO"
    printf 'datastoreEndpoint: "%s"\n' "$KUSCIA_MASTER_DATASTORE_ENDPOINT"
    echo "protocol: MTLS"
  } > "$output"
}

write_lite_config() {
  local domain="$1" namespace="$2" key_file="$3" token_file="$4" dns_ip="$5" node="$6" output="$7"
  local encoded_key
  encoded_key="$(base64 < "$key_file" | tr -d '\n')"
  {
    echo "mode: lite"
    echo "domainID: ${domain}"
    printf 'domainKeyData: "%s"\n' "$encoded_key"
    echo "logLevel: INFO"
    echo "liteDeployToken: $(cat "$token_file")"
    echo "masterEndpoint: https://kuscia-master.kuscia-master.svc.cluster.local:1080"
    echo "runtime: runk"
    echo "runk:"
    echo "  namespace: ${namespace}"
    echo "  dnsServers:"
    echo "    - ${dns_ip}"
    echo "  kubeconfigFile:"
    echo "agent:"
    echo "  provider:"
    echo "    k8s:"
    echo "      affinitiesToAdd:"
    echo "        nodeAffinity:"
    echo "          requiredDuringSchedulingIgnoredDuringExecution:"
    echo "            nodeSelectorTerms:"
    echo "              - matchExpressions:"
    echo "                  - key: kubernetes.io/hostname"
    echo "                    operator: In"
    echo "                    values: [${node}]"
    echo "capacity:"
    echo "  cpu: 8"
    echo "  memory: 16Gi"
    echo "  pods: 100"
    echo "  storage: 100Gi"
    echo "protocol: MTLS"
    echo "image:"
    echo "  pullPolicy: IfNotPresent"
    echo '  defaultRegistry: ""'
    echo "  registries: []"
  } > "$output"
}

kubectl apply -f "$here/namespaces-services-rbac.yaml"
domain_key > "$work/master.key"
write_master_config "$work/master.key" "$work/master.yaml"
kubectl -n kuscia-master create secret generic kuscia-config \
  --from-file=kuscia.yaml="$work/master.yaml" --dry-run=client -o yaml | kubectl apply -f -

# Lite Deployments are accepted before their Secrets exist and start only after
# the Master has minted the one-use deployment tokens below.
kubectl apply -f "$here/deployments.yaml"
kubectl -n kuscia-master rollout restart deploy/kuscia-master
kubectl -n kuscia-master rollout status deploy/kuscia-master --timeout="$rollout_timeout"

for letter in a b c; do
  namespace="kuscia-${letter}"
  domain="domain-${letter}"
  domain_key > "$work/${letter}.key"
  kubectl -n kuscia-master exec deploy/kuscia-master -- \
    scripts/deploy/add_domain_lite.sh "$domain" "$master_domain" \
    > "$work/${letter}.token"
  test -s "$work/${letter}.token"
  dns_ip="$(kubectl -n "$namespace" get svc kuscia-lite -o jsonpath='{.spec.clusterIP}')"
  test -n "$dns_ip"
  case "$letter" in
    a) node="$NODE_A" ;;
    b) node="$NODE_B" ;;
    c) node="$NODE_C" ;;
  esac
  write_lite_config "$domain" "$namespace" "$work/${letter}.key" \
    "$work/${letter}.token" "$dns_ip" "$node" "$work/${letter}.yaml"
  kubectl -n "$namespace" create secret generic kuscia-config \
    --from-file=kuscia.yaml="$work/${letter}.yaml" --dry-run=client -o yaml | kubectl apply -f -
done

for letter in a b c; do
  kubectl -n "kuscia-${letter}" rollout restart deploy/kuscia-lite
  kubectl -n "kuscia-${letter}" rollout status deploy/kuscia-lite --timeout="$rollout_timeout"
done

for source in a b c; do
  for destination in a b c; do
    [[ "$source" == "$destination" ]] && continue
    kubectl -n kuscia-master exec deploy/kuscia-master -- \
      scripts/deploy/create_cluster_domain_route.sh \
      "domain-${source}" "domain-${destination}" \
      "https://kuscia-lite.kuscia-${destination}.svc.cluster.local:1080"
  done
done

for source in a b c; do
  for destination in a b c; do
    [[ "$source" == "$destination" ]] && continue
    route="domain-${source}-domain-${destination}"
    kubectl -n kuscia-master exec deploy/kuscia-master -- \
      kubectl wait --for='jsonpath={.status.conditions[?(@.type=="Ready")].status}=True' \
      "clusterdomainroute/${route}" --timeout="$rollout_timeout"
  done
done

kubectl -n kuscia-master exec deploy/kuscia-master -- kubectl get clusterdomainroutes \
  -o custom-columns=NAME:.metadata.name,READY:'.status.conditions[?(@.type=="Ready")].status'
