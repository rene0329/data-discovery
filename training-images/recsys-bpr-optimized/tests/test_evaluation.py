import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

os.environ.setdefault('OUTPUT_DIR', tempfile.mkdtemp(prefix='bpr-tests-'))
spec = importlib.util.spec_from_file_location('train', Path(__file__).resolve().parents[1] / 'app' / 'train.py')
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)
np = train.np


def reference_evaluate(model, train_pos, test_pos, k):
    values = []
    for uid, positives in test_pos.items():
        if not positives:
            continue
        scores = model.bu[uid] + model.bi + model.U[uid] @ model.I.T
        scores[list(train_pos.get(uid, set()))] = -np.inf
        ranked = np.argsort(scores)[::-1][:k]
        hits = sum(item in positives for item in ranked)
        ideal = min(len(positives), k)
        values.append((hits / k, hits / len(positives),
                       train._dcg(ranked, positives, k) / sum(1 / np.log2(r + 2) for r in range(ideal))))
    return tuple(np.mean(values, axis=0)) if values else (0., 0., 0.)


class EvaluationTest(unittest.TestCase):
    def test_top_k_matches_full_sort(self):
        scores = np.random.RandomState(3).normal(size=(17, 503))
        scores[:, :7] = -np.inf
        for k in (1, 10, 503, 600):
            with self.subTest(k=k):
                np.testing.assert_array_equal(train.top_k_items(scores, k),
                                              np.argsort(scores, axis=1)[:, ::-1][:, :k])

    def test_ties_and_exhausted_candidates_keep_previous_order(self):
        scores = np.array([[2, 2, 2, 1, 0], [9, 8, 8, 8, 7],
                           [-np.inf] * 5, [3, -np.inf, -np.inf, -np.inf, -np.inf]], dtype=float)
        for k in (1, 2, 4):
            np.testing.assert_array_equal(train.top_k_items(scores, k),
                                          np.argsort(scores, axis=1)[:, ::-1][:, :k])

    def test_empty_catalog(self):
        self.assertEqual(train.top_k_items(np.empty((2, 0)), 10).shape, (2, 0))

    def test_full_metrics_match_reference_for_multiple_batches(self):
        np.random.seed(42)
        model = train.BPRModel(19, 97, 8, .01, .001)
        training = {u: {u, u + 19} for u in range(19)}
        testing = {u: {(u + 40) % 97, (u + 60) % 97} for u in range(19)}
        testing[2] = set()
        training.pop(3)
        for k in (1, 10, 100):
            for batch in (1, 4, 128):
                with self.subTest(k=k, batch=batch):
                    np.testing.assert_allclose(train.evaluate(model, training, testing, k, batch),
                                               reference_evaluate(model, training, testing, k), rtol=0, atol=1e-15)

    def test_recommend_excludes_seen_items(self):
        model = train.BPRModel(1, 20, 3, .01, .001)
        self.assertFalse(set(model.recommend(0, {0, 1, 2}, 10)) & {0, 1, 2})

    def test_empty_test_set(self):
        model = train.BPRModel(2, 4, 3, .01, .001)
        self.assertEqual(train.evaluate(model, {}, {0: set()}, 10), (0., 0., 0.))

    def test_invalid_parameters(self):
        model = train.BPRModel(1, 2, 3, .01, .001)
        for k, batch in ((0, 1), (10, 0), (10, -1)):
            with self.assertRaises(ValueError):
                train.evaluate(model, {}, {}, k, batch)

    def test_final_metrics_are_reused_only_for_unchanged_model(self):
        frame = train.pd.DataFrame({'user_id': ['a', 'b'], 'item_id': ['x', 'y']})
        with tempfile.TemporaryDirectory() as output:
            for epochs, expected_evaluations in ((1, 1), (2, 2), (5, 2), (6, 3)):
                with self.subTest(epochs=epochs), patch.object(train, 'N_EPOCHS', epochs), \
                     patch.object(train, 'OUTPUT_DIR', output), patch.object(train.os.path, 'isfile', return_value=True), \
                     patch.object(train, 'load_npz', return_value=frame), \
                     patch.object(train, 'split_train_test', return_value=({0: {0}}, {0: {1}})), \
                     patch.object(train.BPRModel, 'train_epoch', return_value=.5), \
                     patch.object(train, 'evaluate', return_value=(.1, .2, .3)) as evaluate:
                    train.main()
                    self.assertEqual(evaluate.call_count, expected_evaluations)
                    self.assertIn('ndcg_at_10=0.3000', Path(output, 'metrics.log').read_text())


if __name__ == '__main__':
    unittest.main()
