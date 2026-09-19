ARG PSI_BUILDER_BASE=ghcr.io/rene0329/data-discovery-practice-server:privacy-secretflow-release-ci-20250228-amd64@sha256:b84f9cacf93a20963811addc794ef1b92ccc3a86c7e94c7994a91e2f10f305c0
ARG SECRETFLOW_BASE=ghcr.io/rene0329/data-discovery-practice-server:privacy-secretflow-lite-1.11.0b1-amd64@sha256:f0033a79e71f1f76656230d0f3cd31cbddc8d3bdcdb39fe2fd2afc8b000ec178

FROM ${PSI_BUILDER_BASE} AS psi-builder
ARG PSI_VERSION=v0.6.0.dev260105
ARG PSI_COMMIT=72f3312fd9142ea567f3a170d0808a2118b75af8
WORKDIR /build
RUN git clone --depth 1 --branch "${PSI_VERSION}" https://github.com/secretflow/psi.git source \
    && test "$(git -C /build/source rev-parse HEAD)" = "${PSI_COMMIT}"
WORKDIR /build/source
RUN conda install -y perl=5.32.1 \
    && conda update -y sysroot_linux-64 \
    && bazel build //psi/apps/psi_launcher:main -c opt --config=linux-release \
       --repository_cache=/tmp/bazel_repo_cache --remote_timeout=300s --remote_retries=10 \
    && install -D -m 0555 bazel-bin/psi/apps/psi_launcher/main /out/main \
    && install -D -m 0444 examples/pir/apsi/parameters/100K-1-16.json /out/parameters/100K-1-16.json \
    && printf '%s\n' "${PSI_COMMIT}" > /out/SOURCE_REVISION

FROM ${SECRETFLOW_BASE}

USER root
COPY runner/topic4_privacy_runner.py /opt/topic4/bin/topic4_privacy_runner.py
COPY runner/topic4_privacy_gateway.py /opt/topic4/bin/topic4_privacy_gateway.py
COPY --from=psi-builder /out/ /opt/psi/
COPY providers/psi/run-engine.py /opt/topic4/bin/psi-run-engine.py
COPY providers/secretflow/run-engine.py /opt/topic4/bin/secretflow-run-engine.py
COPY providers/secretflow/unified-dispatch.py /opt/topic4/bin/run-engine.py
COPY providers/secretflow/secretflow-engine-check.py /opt/topic4/bin/secretflow-engine-check.py
COPY runner/kuscia-runtime-entry.py /opt/topic4/bin/kuscia-runtime-entry.py
COPY providers/psi/provider.json /opt/topic4/provider-apsi.json
COPY providers/secretflow/provider.json /opt/topic4/provider-secretflow.json
COPY dependencies.lock.json THIRD_PARTY_NOTICES.md /opt/topic4/licenses/

RUN chmod 0555 /opt/topic4/bin/*.py /opt/psi/main \
    && mkdir -p /var/lib/topic4-privacy/jobs /var/lib/topic4-privacy/models /var/run/topic4-inputs /var/run/topic4-work \
    && chmod 0700 /var/lib/topic4-privacy/jobs /var/lib/topic4-privacy/models /var/run/topic4-inputs /var/run/topic4-work \
    && python3 -c "import secretflow as sf; assert sf.__version__ == '1.11.0b1'" \
    && python3 -c "from secretflow.ml.boost.sgb_v import Sgb; from secretflow.device import HEU, PYU, SPU" \
    && test -f /opt/psi/parameters/100K-1-16.json \
    && test "$(cat /opt/psi/SOURCE_REVISION)" = "72f3312fd9142ea567f3a170d0808a2118b75af8"

LABEL org.opencontainers.image.title="Topic4 SecretFlow PSI, HEU and SecureBoost runtime" \
      org.opencontainers.image.version="1.11.0b1" \
      org.opencontainers.image.source="https://github.com/secretflow/secretflow" \
      org.opencontainers.image.revision="b9d6fd5ca9dbdfc95cfda5c282e93022e1caebc4" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV TOPIC4_PROVIDER_CONFIG=/opt/topic4/provider-secretflow.json \
    TOPIC4_STATE_DIR=/var/lib/topic4-privacy/jobs \
    TOPIC4_INPUT_DIR=/var/run/topic4-inputs \
    TOPIC4_WORK_DIR=/var/run/topic4-work \
    TOPIC4_SECRETFLOW_MODEL_ROOT=/var/lib/topic4-privacy/models \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

EXPOSE 8080 5300 61001 62001
ENTRYPOINT ["python3", "/opt/topic4/bin/topic4_privacy_runner.py"]
