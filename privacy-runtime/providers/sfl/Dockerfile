ARG PYTHON_BASE=ghcr.io/rene0329/data-discovery-practice-server:privacy-python-3.10.3-bullseye-amd64@sha256:460573a11357df4f338df5b5afe8ac1fddb6338391a318296fe9b13bd919319b
FROM ${PYTHON_BASE}
ARG SFL_COMMIT=c383e40f665063d7f7d87e437a73e015f87c435c
ARG SFL_SOURCE_SHA256=ecf68f1bc97fdd4db08f16a56899e273013b442bce76d09e99f060e973cb2938
ARG SFL_LOCAL_BUILD_VERSION=1.0.0.dev0+c383e40f
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
# The source archive is produced with git archive at the pinned commit and is
# checked before extraction. The build never resolves a moving branch or tag.
COPY providers/sfl/runtime-requirements.txt /tmp/sfl-runtime-requirements.txt
RUN python3 -m pip install --no-cache-dir \
      --index-url "${PIP_INDEX_URL}" \
      --requirement /tmp/sfl-runtime-requirements.txt
COPY providers/sfl/sfl-c383e40f665063d7f7d87e437a73e015f87c435c.tar.gz /tmp/sfl-source.tar.gz
RUN echo "${SFL_SOURCE_SHA256}  /tmp/sfl-source.tar.gz" | sha256sum --check --strict \
    && mkdir /opt/sfl \
    && tar -xzf /tmp/sfl-source.tar.gz --strip-components=1 -C /opt/sfl \
    && printf '%s\n' "${SFL_COMMIT}" > /opt/sfl/SOURCE_REVISION \
    && python3 -c 'import pathlib,sys; p=pathlib.Path("/opt/sfl/sfl/version.py"); s=p.read_text(); s=s.replace("1.0.0.dev$$DATE$$", sys.argv[1]).replace("$$COMMIT_ID$$", sys.argv[2]); p.write_text(s); assert sys.argv[1] in s and sys.argv[2] in s' \
      "${SFL_LOCAL_BUILD_VERSION}" "${SFL_COMMIT}" \
    && PIP_INDEX_URL="${PIP_INDEX_URL}" python3 -m pip install \
      --no-cache-dir --no-deps /opt/sfl \
    && rm -f /tmp/sfl-source.tar.gz
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
