#!/usr/bin/env bash
set -euo pipefail

# Fixed Master + A/B/C Lite RunK bootstrap based on Kuscia v1.2.0b0 hack/k8s.
# Domain keys and deploy tokens stay in a mode-0700 temporary directory.

readonly here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly master_domain="${KUSCIA_MASTER_DOMAIN_ID:-topic4-master}"
readonly rollout_timeout="${KUSCIA_ROLLOUT_TIMEOUT:-10m}"
readonly rotate_domain_credentials="${KUSCIA_ROTATE_DOMAIN_CREDENTIALS:-0}"
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
case "$rotate_domain_credentials" in
  0|1) ;;
  *) echo "KUSCIA_ROTATE_DOMAIN_CREDENTIALS must be 0 or 1" >&2; exit 2 ;;
esac

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
# The applied manifest contains the RSA key and datastore credential.  Keep
# those only in Secret.data, not in kubectl's last-applied metadata copy.
kubectl -n kuscia-master annotate secret kuscia-config \
  kubectl.kubernetes.io/last-applied-configuration- >/dev/null

# Lite Deployments are accepted before their Secrets exist and start only after
# the Master has minted the one-use deployment tokens below.
kubectl apply -f "$here/deployments.yaml"
kubectl -n kuscia-master rollout restart deploy/kuscia-master
kubectl -n kuscia-master rollout status deploy/kuscia-master --timeout="$rollout_timeout"
master_pod="$(kubectl -n kuscia-master get pods -l app=kuscia-master \
  --field-selector=status.phase=Running --sort-by=.metadata.creationTimestamp \
  -o name | tail -n 1)"
test -n "$master_pod"
kubectl -n kuscia-master wait --for=condition=Ready "$master_pod" --timeout="$rollout_timeout"

# A stopped Master Gateway remains live to Kuscia for three minutes and blocks
# new Lite route-token handshakes.  Keep every Gateway backed by an outer pod,
# including the selected Ready Master, and remove only orphaned Gateway CRs.
bash "$here/cleanup-stale-master-gateways.sh" "$master_pod" "$master_domain"

# A Domain deploy token can only enroll the key used for that handshake.  A
# repeated apply does not clear the old Domain status or route credentials, so
# an explicit credential rotation must remove those logical trust records
# before new one-use tokens are minted.  The dedicated Topic4 Master retains
# its database, AppImages and KusciaDeployments; the fixed domain namespaces
# are recreated by the controllers after enrollment.
if [[ "$rotate_domain_credentials" == 1 ]]; then
  # Credential rotation is a maintenance operation.  Remove Topic4's fixed
  # provider deployments while every Lite RunK agent is still available so
  # it can terminate projected outer Pods before the logical namespaces go.
  for provider in apsi secretflow sfl; do
    kubectl -n kuscia-master exec "$master_pod" -- \
      kubectl -n cross-domain delete kusciadeployment \
      "topic4-privacy-${provider}-parties" --ignore-not-found=true --wait=true
  done
  for letter in a b c; do
    for provider in apsi secretflow sfl; do
      projected_pods="$(kubectl -n "kuscia-${letter}" get pods \
        -l "kuscia.secretflow/kd-name=topic4-privacy-${provider}-parties" \
        -o 'jsonpath={range .items[*]}{.metadata.name}{"\n"}{end}')"
      while IFS= read -r projected_pod; do
        [[ -z "$projected_pod" ]] && continue
        kubectl -n "kuscia-${letter}" wait --for=delete "pod/${projected_pod}" \
          --timeout="$rollout_timeout"
      done <<< "$projected_pods"
    done
  done
  for provider in apsi secretflow sfl; do
    kubectl -n kuscia-master exec "$master_pod" -- \
      kubectl -n cross-domain delete appimage "topic4-privacy-${provider}-image" \
      --ignore-not-found=true --wait=true
  done
  # Stop every Lite agent before deleting token state.  A running Lite agent
  # immediately recreates its namespaced DomainRoute with the old Pod as the
  # revision initializer, racing the cleanup below.
  for letter in a b c; do
    active_pods="$(kubectl -n "kuscia-${letter}" get pods -l app=kuscia-lite \
      -o 'jsonpath={range .items[?(@.status.containerStatuses[0].ready==true)]}{.metadata.name}{"\n"}{end}')"
    kubectl -n "kuscia-${letter}" scale deploy/kuscia-lite --replicas=0
    while IFS= read -r active_pod; do
      [[ -z "$active_pod" ]] && continue
      kubectl -n "kuscia-${letter}" wait --for=delete "pod/${active_pod}" \
        --timeout="$rollout_timeout"
    done <<< "$active_pods"
    kubectl -n "kuscia-${letter}" rollout status deploy/kuscia-lite \
      --timeout="$rollout_timeout"
  done
  route_names=()
  for source in a b c; do
    route_names+=("domain-${source}-${master_domain}")
    for destination in a b c; do
      [[ "$source" == "$destination" ]] && continue
      route_names+=("domain-${source}-domain-${destination}")
    done
  done
  kubectl -n kuscia-master exec "$master_pod" -- \
    kubectl delete clusterdomainroutes "${route_names[@]}" \
    --ignore-not-found=true --wait=true
  # ClusterDomainRoute deletion does not remove the per-domain DomainRoute
  # token records in Kuscia 1.2.  Those records retain the previous Pod as
  # revisionInitializer and otherwise keep every replacement token unready.
  for domain_namespace in "$master_domain" domain-a domain-b domain-c; do
    kubectl -n kuscia-master exec "$master_pod" -- \
      kubectl -n "$domain_namespace" delete domainroutes --all \
      --ignore-not-found=true --wait=true
  done
  kubectl -n kuscia-master exec "$master_pod" -- \
    kubectl delete domains domain-a domain-b domain-c \
    --ignore-not-found=true --wait=true
  # The Domain controller intentionally leaves logical namespaces behind.
  # Recreate them during a credential rotation so their ServiceAccount UID,
  # projected API token and any route authorization headers are invalidated.
  kubectl -n kuscia-master exec "$master_pod" -- \
    kubectl delete namespaces domain-a domain-b domain-c \
    --ignore-not-found=true --wait=true --timeout="$rollout_timeout"
fi

for letter in a b c; do
  namespace="kuscia-${letter}"
  domain="domain-${letter}"
  domain_key > "$work/${letter}.key"
  kubectl -n kuscia-master exec "$master_pod" -- \
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
  kubectl -n "$namespace" annotate secret kuscia-config \
    kubectl.kubernetes.io/last-applied-configuration- >/dev/null
done

for letter in a b c; do
  kubectl -n "kuscia-${letter}" rollout restart deploy/kuscia-lite
  if [[ "$rotate_domain_credentials" == 1 ]]; then
    kubectl -n "kuscia-${letter}" scale deploy/kuscia-lite --replicas=1
  fi
  kubectl -n "kuscia-${letter}" rollout status deploy/kuscia-lite --timeout="$rollout_timeout"
done

# The outer Deployment can become Ready just before the Lite Gateway
# controller registers its namespaced Gateway CR. Wait for the exact current
# Pod and a non-empty heartbeat before route-token reconciliation.
for letter in a b c; do
  namespace="kuscia-${letter}"
  domain="domain-${letter}"
  lite_pod="$(kubectl -n "$namespace" get pods -l app=kuscia-lite \
    --field-selector=status.phase=Running \
    -o 'jsonpath={range .items[?(@.status.containerStatuses[0].ready==true)]}{.metadata.name}{"\n"}{end}' \
    | tail -n 1)"
  test -n "$lite_pod"
  timeout "$rollout_timeout" bash -c '
    set -euo pipefail
    while true; do
      heartbeat="$(kubectl -n kuscia-master exec "$1" -- \
        kubectl -n "$2" get "gateway/$3" \
        -o "jsonpath={.status.heartbeatTime}" 2>/dev/null || true)"
      [[ -n "$heartbeat" ]] && exit 0
      sleep 2
    done
  ' _ "$master_pod" "$domain" "$lite_pod" || {
    echo "Lite Gateway registration timed out for ${domain}/${lite_pod}" >&2
    exit 1
  }
done

for source in a b c; do
  route="domain-${source}-${master_domain}"
  kubectl -n kuscia-master exec "$master_pod" -- \
    kubectl wait --for='jsonpath={.status.conditions[?(@.type=="Ready")].status}=True' \
    "clusterdomainroute/${route}" --timeout="$rollout_timeout"
done

for source in a b c; do
  for destination in a b c; do
    [[ "$source" == "$destination" ]] && continue
    kubectl -n kuscia-master exec "$master_pod" -- \
      scripts/deploy/create_cluster_domain_route.sh \
      "domain-${source}" "domain-${destination}" \
      "https://kuscia-lite.kuscia-${destination}.svc.cluster.local:1080"
  done
done

for source in a b c; do
  for destination in a b c; do
    [[ "$source" == "$destination" ]] && continue
    route="domain-${source}-domain-${destination}"
    kubectl -n kuscia-master exec "$master_pod" -- \
      kubectl wait --for='jsonpath={.status.conditions[?(@.type=="Ready")].status}=True' \
      "clusterdomainroute/${route}" --timeout="$rollout_timeout"
  done
done

kubectl -n kuscia-master exec "$master_pod" -- kubectl get clusterdomainroutes \
  -o custom-columns=NAME:.metadata.name,READY:'.status.conditions[?(@.type=="Ready")].status'
