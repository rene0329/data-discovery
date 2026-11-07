"""
BPR (Bayesian Personalized Ranking) 推荐系统训练脚本
-------------------------------------------------------
数据格式 (ratings.npz):
    user_ids  - 用户 ID 数组（int 或 str）
    item_ids  - 物品 ID 数组（int 或 str）
    两个数组等长，第 i 条表示一次交互记录。

    打包示例:
        import numpy as np
        np.savez('ratings.npz', user_ids=user_arr, item_ids=item_arr)

环境变量:
    DATA_PATH   - ratings.npz 的完整路径，例如 /data/ratings.npz
    OUTPUT_DIR  - 日志输出目录，默认 /data/output
    TASK_TYPE   - recsys
    MODEL_TYPE  - bpr
    DATA_NAME   - 数据集文件名
    EVAL_BATCH_SIZE - 每批评估用户数，默认 32；仍评估全部用户和物品
    EVAL_WORKERS - 前 k 个推荐候选的排序线程数，默认 2
"""

import os
import sys
import time
import logging
import numpy as np
import pandas as pd
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from functools import partial
from scipy.linalg.blas import dgemm

# ─────────────────────── 日志配置 ───────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stdout)],
)
log = logging.getLogger(__name__)

# ─────────────────────── 环境变量 ───────────────────────
DATA_PATH  = os.getenv("DATA_PATH",  "/data/ratings.npz")
OUTPUT_DIR = os.getenv("OUTPUT_DIR", "/data/output")
TASK_TYPE  = os.getenv("TASK_TYPE",  "recsys")
MODEL_TYPE = os.getenv("MODEL_TYPE", "bpr")
DATA_NAME  = os.getenv("DATA_NAME",  "ratings.txt")

# ─────────────────────── 超参数 ───────────────────────
N_FACTORS   = int(os.getenv("N_FACTORS",  "8"))
LR          = float(os.getenv("LR",       "0.01"))
REG         = float(os.getenv("REG",      "0.001"))
N_EPOCHS    = int(os.getenv("N_EPOCHS",   "30"))
N_SAMPLES   = int(os.getenv("N_SAMPLES",  "5"))   # per positive sample
TOP_K       = int(os.getenv("TOP_K",      "10"))
TEST_RATIO  = float(os.getenv("TEST_RATIO","0.2"))
SEED        = int(os.getenv("SEED",       "42"))
EVAL_BATCH_SIZE = int(os.getenv("EVAL_BATCH_SIZE", "32"))
EVAL_WORKERS = int(os.getenv("EVAL_WORKERS", "2"))

np.random.seed(SEED)
os.makedirs(OUTPUT_DIR, exist_ok=True)


# ═══════════════════════════════════════════════════════
#  1. 数据读取
# ═══════════════════════════════════════════════════════
def load_npz(path: str) -> pd.DataFrame:
    """从 .npz 文件加载交互数据，返回 DataFrame(user_id, item_id)。
    npz 必须包含:
      user_ids  - 用户 ID 序列（int 或 str）
      item_ids  - 物品 ID 序列（int 或 str）
    """
    data = np.load(path, allow_pickle=True)
    df = pd.DataFrame({
        "user_id": data["user_ids"].astype(str),
        "item_id": data["item_ids"].astype(str),
    })
    return df.drop_duplicates()


def build_mappings(df: pd.DataFrame):
    users = sorted(df["user_id"].unique())
    items = sorted(df["item_id"].unique())
    u2i  = {u: i for i, u in enumerate(users)}
    i2i  = {v: i for i, v in enumerate(items)}
    return u2i, i2i, len(users), len(items)


def split_train_test(df: pd.DataFrame, u2i, i2i, test_ratio: float):
    """每个用户留最后 test_ratio 比例的交互做测试集"""
    df = df.copy()
    df["uid"] = df["user_id"].map(u2i)
    df["iid"] = df["item_id"].map(i2i)
    df = df.dropna(subset=["uid", "iid"])
    df["uid"] = df["uid"].astype(int)
    df["iid"] = df["iid"].astype(int)

    train_rows, test_rows = [], []
    for uid, grp in df.groupby("uid"):
        grp = grp.sample(frac=1, random_state=SEED)  # shuffle
        n   = max(1, int(len(grp) * test_ratio))
        test_rows.append(grp.iloc[:n])
        train_rows.append(grp.iloc[n:])

    train = pd.concat(train_rows)
    test  = pd.concat(test_rows)

    train_pos = defaultdict(set)
    for row in train.itertuples():
        train_pos[row.uid].add(row.iid)

    test_pos  = defaultdict(set)
    for row in test.itertuples():
        test_pos[row.uid].add(row.iid)

    return train_pos, test_pos


# ═══════════════════════════════════════════════════════
#  2. BPR 模型
# ═══════════════════════════════════════════════════════
class BPRModel:
    """Matrix Factorization with BPR loss (SGD)"""

    def __init__(self, n_users: int, n_items: int, n_factors: int, lr: float, reg: float):
        self.n_users   = n_users
        self.n_items   = n_items
        self.n_factors = n_factors
        self.lr        = lr
        self.reg       = reg
        scale          = 0.01
        self.U = np.random.normal(0, scale, (n_users, n_factors))
        self.I = np.random.normal(0, scale, (n_items, n_factors))
        self.bu = np.zeros(n_users)
        self.bi = np.zeros(n_items)

    def _sigmoid(self, x):
        return 1.0 / (1.0 + np.exp(-x))

    def train_epoch(self, train_pos: dict, n_samples: int) -> float:
        """一个 epoch 的 BPR SGD 更新，返回平均 BPR-Loss"""
        total_loss = 0.0
        n_updates  = 0
        all_items  = np.arange(self.n_items)

        for uid, pos_set in train_pos.items():
            pos_list = list(pos_set)
            if not pos_list:
                continue
            for iid in pos_list:
                for _ in range(n_samples):
                    # 负样本采样
                    jid = np.random.randint(0, self.n_items)
                    while jid in pos_set:
                        jid = np.random.randint(0, self.n_items)

                    x_uij = (self.bu[uid] + self.bi[iid] - self.bi[jid]
                              + self.U[uid] @ (self.I[iid] - self.I[jid]))

                    sigmoid = self._sigmoid(-x_uij)
                    total_loss -= np.log(1e-10 + self._sigmoid(x_uij))

                    # 梯度更新
                    self.U[uid]  += self.lr * (sigmoid * (self.I[iid] - self.I[jid]) - self.reg * self.U[uid])
                    self.I[iid]  += self.lr * (sigmoid * self.U[uid] - self.reg * self.I[iid])
                    self.I[jid]  -= self.lr * (sigmoid * self.U[uid] + self.reg * self.I[jid])
                    self.bu[uid] += self.lr * (sigmoid - self.reg * self.bu[uid])
                    self.bi[iid] += self.lr * (sigmoid - self.reg * self.bi[iid])
                    self.bi[jid] -= self.lr * (sigmoid + self.reg * self.bi[jid])

                    n_updates += 1

        return total_loss / max(1, n_updates)

    def recommend(self, uid: int, exclude: set, top_k: int) -> np.ndarray:
        scores = self.bu[uid] + self.bi + self.U[uid] @ self.I.T
        scores[list(exclude)] = -np.inf
        return top_k_items(scores[np.newaxis, :], top_k)[0]


def top_k_items(scores: np.ndarray, top_k: int) -> np.ndarray:
    """Select exact top-k items; retain the original full-sort ordering for ties."""
    if top_k <= 0:
        raise ValueError("TOP_K must be positive")
    k = min(top_k, scores.shape[1])
    if k == 0:
        return np.empty((len(scores), 0), dtype=np.intp)
    if k == scores.shape[1]:
        return np.argsort(scores, axis=1)[:, ::-1]
    candidates = np.argpartition(scores, scores.shape[1] - k, axis=1)[:, -k:]
    candidate_scores = np.take_along_axis(scores, candidates, axis=1)
    order = np.argsort(candidate_scores, axis=1)[:, ::-1]
    ranked = np.take_along_axis(candidates, order, axis=1)
    ranked_scores = np.take_along_axis(candidate_scores, order, axis=1)
    # argpartition does not define ordering for equal scores, including -inf.
    # Fall back only for affected rows so the previous metric semantics survive.
    tied_inside = np.any(ranked_scores[:, 1:] == ranked_scores[:, :-1], axis=1)
    tied_boundary = np.sum(scores == ranked_scores[:, -1, None], axis=1) > 1
    for row in np.flatnonzero(tied_inside | tied_boundary):
        ranked[row] = np.argsort(scores[row])[::-1][:k]
    return ranked


# ═══════════════════════════════════════════════════════
#  3. 评估指标
# ═══════════════════════════════════════════════════════
def _dcg(ranked_list, pos_set, k):
    dcg = 0.0
    for rank, iid in enumerate(ranked_list[:k], start=1):
        if iid in pos_set:
            dcg += 1.0 / np.log2(rank + 1)
    return dcg


def score_batch(model, users, item_factors_f):
    """Accumulate the dot products into the biases without a second score matrix."""
    # BLAS writes column-major (items x users); its transpose is row-contiguous
    # for argpartition, so neither scoring nor ranking needs a layout copy.
    scores_t = np.empty((len(model.I), len(users)), dtype=np.float64, order="F")
    np.add(model.bi[:, None], model.bu[users], out=scores_t)
    scores_t = dgemm(alpha=1., a=item_factors_f,
                     b=np.asfortranarray(model.U[users]), trans_b=True,
                     beta=1., c=scores_t, overwrite_c=True)
    return scores_t.T


def evaluate(model: BPRModel, train_pos: dict, test_pos: dict, top_k: int,
             batch_size: int = EVAL_BATCH_SIZE, workers: int = EVAL_WORKERS):
    if batch_size <= 0:
        raise ValueError("EVAL_BATCH_SIZE must be positive")
    if top_k <= 0:
        raise ValueError("TOP_K must be positive")
    if workers <= 0:
        raise ValueError("EVAL_WORKERS must be positive")
    precisions, recalls, ndcgs = [], [], []
    users = [uid for uid, pos_set in test_pos.items() if pos_set]
    item_factors_f = np.asfortranarray(model.I)
    started = last_progress = time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for offset in range(0, len(users), batch_size):
            batch = users[offset:offset + batch_size]
            scores = score_batch(model, batch, item_factors_f)
            for row, uid in enumerate(batch):
                scores[row, list(train_pos.get(uid, set()))] = -np.inf
            if workers == 1:
                recommendations = top_k_items(scores, top_k)
            else:
                chunks = np.array_split(scores, min(workers, len(batch)), axis=0)
                recommendations = np.concatenate(list(pool.map(partial(top_k_items, top_k=top_k), chunks)))
            for uid, recs in zip(batch, recommendations):
                pos_set = test_pos[uid]
                hits = sum(1 for r in recs if r in pos_set)
                ideal = min(len(pos_set), top_k)
                precisions.append(hits / top_k)
                recalls.append(hits / len(pos_set))
                ndcgs.append(_dcg(recs, pos_set, top_k) / (
                    sum(1.0 / np.log2(r + 2) for r in range(ideal))))
            now = time.monotonic()
            completed = offset + len(batch)
            if now - last_progress >= 5 or completed == len(users):
                log.info("评估进度: %d/%d 用户 | %.1fs", completed, len(users), now - started)
                last_progress = now
    return (np.mean(precisions) if precisions else 0.0,
            np.mean(recalls)    if recalls    else 0.0,
            np.mean(ndcgs)      if ndcgs      else 0.0)


# ═══════════════════════════════════════════════════════
#  4. 主流程
# ═══════════════════════════════════════════════════════
def main():
    log.info("=" * 60)
    log.info("任务类型: %s | 模型类型: %s", TASK_TYPE, MODEL_TYPE)
    log.info("数据文件: %s", DATA_PATH)
    log.info("超参数 — n_factors=%d, lr=%.4f, reg=%.5f, epochs=%d",
             N_FACTORS, LR, REG, N_EPOCHS)
    log.info("评估配置 — 每批用户数=%d，排序线程数=%d", EVAL_BATCH_SIZE, EVAL_WORKERS)
    log.info("=" * 60)

    # 1. 读取数据
    if not os.path.isfile(DATA_PATH):
        log.error("数据文件不存在: %s", DATA_PATH)
        sys.exit(1)

    df = load_npz(DATA_PATH)
    log.info("读取完成: %d 条交互, %d 用户, %d 物品",
             len(df), df["user_id"].nunique(), df["item_id"].nunique())

    u2i, i2i, n_users, n_items = build_mappings(df)
    train_pos, test_pos = split_train_test(df, u2i, i2i, TEST_RATIO)
    n_train = sum(len(v) for v in train_pos.values())
    n_test  = sum(len(v) for v in test_pos.values())
    log.info("训练集: %d 条 | 测试集: %d 条 | 用户数(有测试): %d",
             n_train, n_test, len(test_pos))

    # 2. 初始化模型
    model = BPRModel(n_users, n_items, N_FACTORS, LR, REG)
    log.info("模型参数量: %d", n_users * N_FACTORS + n_items * N_FACTORS + n_users + n_items)

    # 3. 训练循环
    best_recall = 0.0
    last_eval_epoch, last_metrics = None, None
    for epoch in range(1, N_EPOCHS + 1):
        t0   = time.time()
        loss = model.train_epoch(train_pos, N_SAMPLES)
        elapsed = time.time() - t0

        if epoch % 5 == 0 or epoch == 1:
            eval_started = time.monotonic()
            p, r, ndcg = evaluate(model, train_pos, test_pos, TOP_K)
            last_eval_epoch, last_metrics = epoch, (p, r, ndcg)
            log.info("Epoch %3d/%d | loss=%.4f | P@%d=%.4f | R@%d=%.4f | NDCG@%d=%.4f | 训练 %.1fs | 评估 %.1fs",
                     epoch, N_EPOCHS, loss,
                     TOP_K, p, TOP_K, r, TOP_K, ndcg, elapsed,
                     time.monotonic() - eval_started)
            if r > best_recall:
                best_recall = r
        else:
            log.info("Epoch %3d/%d | loss=%.4f | %.1fs", epoch, N_EPOCHS, loss, elapsed)

    # 4. 最终评估
    if last_eval_epoch == N_EPOCHS:
        p, r, ndcg = last_metrics
        log.info("复用第 %d 轮评估结果，模型未发生变化", N_EPOCHS)
    else:
        p, r, ndcg = evaluate(model, train_pos, test_pos, TOP_K)
    log.info("=" * 60)
    log.info("训练完成 — 最终指标 P@%d=%.4f | R@%d=%.4f | NDCG@%d=%.4f",
             TOP_K, p, TOP_K, r, TOP_K, ndcg)
    log.info("Best Recall@%d achieved: %.4f", TOP_K, best_recall)
    log.info("=" * 60)

    # 5. 写入日志文件
    log_path = os.path.join(OUTPUT_DIR, "metrics.log")
    with open(log_path, "w", encoding="utf-8") as f:
        f.write(f"task_type={TASK_TYPE}\n")
        f.write(f"model_type={MODEL_TYPE}\n")
        f.write(f"data_name={DATA_NAME}\n")
        f.write(f"n_users={n_users}\n")
        f.write(f"n_items={n_items}\n")
        f.write(f"n_train={n_train}\n")
        f.write(f"n_test={n_test}\n")
        f.write(f"precision_at_{TOP_K}={p:.4f}\n")
        f.write(f"recall_at_{TOP_K}={r:.4f}\n")
        f.write(f"ndcg_at_{TOP_K}={ndcg:.4f}\n")
    log.info("指标已写入: %s", log_path)


if __name__ == "__main__":
    main()
