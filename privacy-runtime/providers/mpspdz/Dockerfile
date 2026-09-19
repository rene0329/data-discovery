ARG PYTHON_BASE=ghcr.io/rene0329/data-discovery-practice-server:privacy-python-3.10.3-bullseye-amd64@sha256:460573a11357df4f338df5b5afe8ac1fddb6338391a318296fe9b13bd919319b
FROM ${PYTHON_BASE}

ARG MP_SPDZ_VERSION=v0.4.3
ARG MP_SPDZ_COMMIT=26a605368e40fed3a7e9cee78c9a3f4390b85eb5
ARG MP_SPDZ_ASSET_SHA256=4b5f4f7a79f31365288fe4498ba2b70afc314c028696929c8d259ef50ab2e759

# The digest-pinned Python base already carries the small fetch/TLS toolchain.
# Assert it instead of consulting mutable Debian repositories during a release build.
RUN command -v curl \
    && command -v openssl \
    && command -v xz \
    && test -s /etc/ssl/certs/ca-certificates.crt

WORKDIR /opt
RUN curl --fail --location --retry 3 \
      --output /tmp/mp-spdz.tar.xz \
      https://github.com/data61/MP-SPDZ/releases/download/${MP_SPDZ_VERSION}/mp-spdz-0.4.3.tar.xz \
    && echo "${MP_SPDZ_ASSET_SHA256}  /tmp/mp-spdz.tar.xz" | sha256sum --check --strict \
    && tar -xJf /tmp/mp-spdz.tar.xz -C /opt \
    && mv /opt/mp-spdz-0.4.3 /opt/mp-spdz \
    && rm /tmp/mp-spdz.tar.xz \
    && install -m 0555 /opt/mp-spdz/bin/Linux-amd64/malicious-rep-ring-party.x /opt/mp-spdz/malicious-rep-ring-party.x \
    && install -m 0555 /opt/mp-spdz/bin/Linux-amd64/malicious-rep-bin-party.x /opt/mp-spdz/malicious-rep-bin-party.x \
    && printf '%s\n' "${MP_SPDZ_COMMIT}" > /opt/mp-spdz/SOURCE_REVISION \
    && printf '%s\n' "${MP_SPDZ_ASSET_SHA256}" > /opt/mp-spdz/RELEASE_ASSET_SHA256

WORKDIR /opt/mp-spdz
COPY providers/mpspdz/programs/*.mpc /opt/mp-spdz/Programs/Source/
RUN set -eu; \
    for source in Programs/Source/topic4_secure_sum_r*.mpc Programs/Source/topic4_private_stats_r*.mpc; do \
      program="${source##*/}"; ./compile.py -R 64 "${program%.mpc}"; \
    done; \
    for source in Programs/Source/topic4_private_threshold_100_r*.mpc; do \
      program="${source##*/}"; ./compile.py -R 64 "${program%.mpc}"; \
    done

COPY runner/topic4_privacy_runner.py /opt/topic4/bin/topic4_privacy_runner.py
COPY runner/topic4_privacy_gateway.py /opt/topic4/bin/topic4_privacy_gateway.py
COPY providers/mpspdz/run-engine.py providers/mpspdz/csv_to_input.py providers/mpspdz/mpspdz-health.py /opt/topic4/bin/
COPY providers/mpspdz/provider.json /opt/topic4/provider.json
COPY providers/mpspdz/hosts.txt /etc/topic4-mpspdz/hosts.txt
COPY dependencies.lock.json THIRD_PARTY_NOTICES.md /opt/topic4/licenses/
RUN chmod 0555 /opt/topic4/bin/*.py \
    && rm -rf /opt/mp-spdz/Player-Data \
    && mkdir -p /var/lib/topic4-privacy/jobs /var/run/topic4-inputs /var/run/topic4-work /inputs /tls \
    && chmod 0700 /var/lib/topic4-privacy/jobs /var/run/topic4-inputs /var/run/topic4-work

LABEL org.opencontainers.image.title="Topic4 MP-SPDZ privacy runtime" \
      org.opencontainers.image.version="0.4.3" \
      org.opencontainers.image.source="https://github.com/data61/MP-SPDZ" \
      org.opencontainers.image.revision="26a605368e40fed3a7e9cee78c9a3f4390b85eb5" \
      org.opencontainers.image.licenses="BSD-3-Clause"

ENV TOPIC4_PROVIDER_CONFIG=/opt/topic4/provider.json \
    TOPIC4_STATE_DIR=/var/lib/topic4-privacy/jobs \
    TOPIC4_INPUT_DIR=/var/run/topic4-inputs \
    TOPIC4_WORK_DIR=/var/run/topic4-work \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8080 5000
ENTRYPOINT ["python3", "/opt/topic4/bin/topic4_privacy_runner.py"]
