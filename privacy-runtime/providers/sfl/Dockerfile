ARG PYTHON_BASE=ghcr.io/rene0329/data-discovery-practice-server:privacy-python-3.10.3-bullseye-amd64@sha256:460573a11357df4f338df5b5afe8ac1fddb6338391a318296fe9b13bd919319b
FROM ${PYTHON_BASE}
ARG SFL_COMMIT=c383e40f665063d7f7d87e437a73e015f87c435c
ARG SFL_LOCAL_BUILD_VERSION=1.0.0.dev0+c383e40f
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/secretflow/sfl.git /opt/sfl \
    && git -C /opt/sfl checkout --detach "${SFL_COMMIT}" \
    && test "$(git -C /opt/sfl rev-parse HEAD)" = "${SFL_COMMIT}" \
    && printf '%s\n' "${SFL_COMMIT}" > /opt/sfl/SOURCE_REVISION \
    && sed -i "s/1.0.0.dev\$\$DATE\$\$/${SFL_LOCAL_BUILD_VERSION}/" /opt/sfl/sfl/version.py \
    && sed -i "s/\$\$COMMIT_ID\$\$/${SFL_COMMIT}/" /opt/sfl/sfl/version.py \
    && python3 -m pip install --no-cache-dir /opt/sfl
COPY providers/sfl/hfl_fedavg_logreg.py providers/sfl/sfl-engine-check.py runner/topic4_sfl_smoke.py /opt/topic4/bin/
RUN python3 /opt/topic4/bin/hfl_fedavg_logreg.py --self-test
COPY runner/topic4_privacy_runner.py /opt/topic4/bin/topic4_privacy_runner.py
COPY runner/topic4_privacy_gateway.py /opt/topic4/bin/topic4_privacy_gateway.py
COPY runner/kuscia-runtime-entry.py /opt/topic4/bin/kuscia-runtime-entry.py
COPY providers/sfl/provider.json /opt/topic4/provider.json
COPY dependencies.lock.json THIRD_PARTY_NOTICES.md /opt/topic4/licenses/
RUN chmod 0555 /opt/topic4/bin/*.py \
    && mkdir -p /var/lib/topic4-privacy/jobs /var/lib/topic4-privacy/models /var/lib/topic4-privacy/smoke /var/run/topic4-inputs /var/run/topic4-work \
    && chmod 0700 /var/lib/topic4-privacy/jobs /var/lib/topic4-privacy/models /var/lib/topic4-privacy/smoke /var/run/topic4-inputs /var/run/topic4-work
LABEL org.opencontainers.image.title="Topic4 SFL runtime" \
      org.opencontainers.image.version="source-c383e40f" \
      org.opencontainers.image.source="https://github.com/secretflow/sfl" \
      org.opencontainers.image.revision="c383e40f665063d7f7d87e437a73e015f87c435c" \
      org.opencontainers.image.licenses="Apache-2.0"
ENV TOPIC4_PROVIDER_CONFIG=/opt/topic4/provider.json \
    TOPIC4_STATE_DIR=/var/lib/topic4-privacy/jobs \
    TOPIC4_INPUT_DIR=/var/run/topic4-inputs \
    TOPIC4_WORK_DIR=/var/run/topic4-work \
    TOPIC4_MODEL_DIR=/var/lib/topic4-privacy/models \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8080
ENTRYPOINT ["python3", "/opt/topic4/bin/topic4_privacy_runner.py"]
