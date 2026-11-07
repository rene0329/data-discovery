# ──────────────────────────────────────────────
#  BPR 推荐系统训练镜像 (CPU-only, 轻量)
# ──────────────────────────────────────────────
FROM python:3.10-slim

LABEL maintainer="practice-server"
LABEL description="BPR matrix factorization recommender (CPU)"

# 设置工作目录
WORKDIR /app

# Avoid BLAS thread oversubscription on the 4-vCPU cloud nodes.
ENV OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 NUMEXPR_NUM_THREADS=2

# 安装系统依赖（gcc 用于编译 numpy 扩展）
RUN apt-get update && apt-get install -y --no-install-recommends \
        gcc \
    && rm -rf /var/lib/apt/lists/*

# 安装 Python 依赖
COPY app/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# 拷贝训练脚本
COPY app/train.py /app/train.py
COPY tests /tests

# 默认入口
ENTRYPOINT ["python", "/app/train.py"]
