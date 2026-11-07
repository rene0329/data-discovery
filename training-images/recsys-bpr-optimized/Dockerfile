# Reuse the verified original runtime so benchmarks change only evaluation code.
ARG BASE_IMAGE=ghcr.io/rene0329/trainer-recsys-bpr@sha256:9d8327e890df0ab04ab1356b7302d4e461e837d61698f8ec1a369d7a186d644f
FROM ${BASE_IMAGE}

LABEL maintainer="practice-server"
LABEL description="BPR matrix factorization with exact batched evaluation (CPU)"
WORKDIR /app
ENV OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 NUMEXPR_NUM_THREADS=2
COPY app/requirements.txt /app/requirements.txt
RUN python -c "from importlib.metadata import version; from pathlib import Path; requirements=[line.strip().split('==') for line in Path('/app/requirements.txt').read_text().splitlines() if line.strip()]; assert all(version(name)==expected for name,expected in requirements), requirements"
COPY app/train.py /app/train.py
COPY tests /tests
ENTRYPOINT ["python", "/app/train.py"]
