"""Profile exact ranking on a saved trained model, bounded to 512 users."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import time

from test_evaluation import train

output = Path(os.environ['PROFILE_OUTPUT_DIR'])
weights = train.np.load(output / 'validation-model.npz', allow_pickle=False)
U, I, bu, bi = (weights[key] for key in ('U', 'I', 'bu', 'bi'))
users = train.np.linspace(0, len(U) - 1, min(512, len(U)), dtype=int)
results = []
for workers, batch_size in ((1, 8), (1, 16), (1, 32), (1, 64), (1, 128),
                            (2, 16), (2, 32), (2, 64), (2, 128)):
    score_seconds = rank_seconds = 0.
    rankings = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for offset in range(0, len(users), batch_size):
            batch = users[offset:offset + batch_size]
            start = time.perf_counter()
            scores = bu[batch, None] + bi
            scores += U[batch] @ I.T
            score_seconds += time.perf_counter() - start
            start = time.perf_counter()
            if workers == 1:
                ranked = train.top_k_items(scores, 10)
            else:
                chunks = train.np.array_split(scores, workers, axis=0)
                ranked = train.np.concatenate(list(pool.map(lambda x: train.top_k_items(x, 10), chunks)))
            rank_seconds += time.perf_counter() - start
            rankings.append(ranked)
    digest = hashlib.sha256(train.np.concatenate(rankings).tobytes()).hexdigest()
    result = {'workers': workers, 'batch_size': batch_size, 'score_seconds': score_seconds,
              'rank_seconds': rank_seconds, 'total_seconds': score_seconds + rank_seconds,
              'ranking_sha256': digest, 'users': len(users)}
    results.append(result)
    print(json.dumps(result), flush=True)
from types import SimpleNamespace
model = SimpleNamespace(U=U, I=I, bu=bu, bi=bi)
factors_f = train.np.asfortranarray(I)
for batch_size in (16, 32, 64, 128):
    score_seconds = rank_seconds = 0.
    rankings = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        for offset in range(0, len(users), batch_size):
            batch = users[offset:offset + batch_size]
            start = time.perf_counter()
            scores = train.score_batch(model, batch, factors_f)
            score_seconds += time.perf_counter() - start
            start = time.perf_counter()
            ranked = train.np.concatenate(list(pool.map(lambda x: train.top_k_items(x, 10), train.np.array_split(scores, 2))))
            rank_seconds += time.perf_counter() - start
            rankings.append(ranked)
    result = {'fused_scores': True, 'workers': 2, 'batch_size': batch_size, 'score_seconds': score_seconds,
              'rank_seconds': rank_seconds, 'total_seconds': score_seconds + rank_seconds,
              'ranking_sha256': hashlib.sha256(train.np.concatenate(rankings).tobytes()).hexdigest(), 'users': len(users)}
    results.append(result)
    print(json.dumps(result), flush=True)
assert len({r['ranking_sha256'] for r in results}) == 1, 'rankings changed across configurations'
(output / 'evaluation-profile.json').write_text(json.dumps(results, indent=2))
