# Reuse the existing, digest-pinned CPU PyTorch runtime; no network installs.
ARG BASE_IMAGE=10.15.16.40:30455/trainer-text-zh-gru@sha256:416fbf35e3a2d8c0b554713e265269b1e8f7d9f094daa50754f53ca30c97cbd0
FROM ${BASE_IMAGE}
WORKDIR /app
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 OMP_NUM_THREADS=2 MKL_NUM_THREADS=2 OPENBLAS_NUM_THREADS=1
RUN python -c "import torch; assert torch.__version__ == '2.2.2+cpu', torch.__version__"
COPY app/train.py /app/train.py
COPY tests /tests
RUN python -m unittest discover -s /tests -v
ENTRYPOINT ["python", "/app/train.py"]
CMD ["--epochs", "10", "--max-samples", "5000"]
