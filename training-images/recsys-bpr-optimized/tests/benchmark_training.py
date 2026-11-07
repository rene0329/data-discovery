"""Run full training and compare optimized evaluation with the old exact evaluator."""
import json
from pathlib import Path
import time

from test_evaluation import train, reference_evaluate

optimized_evaluate = train.evaluate
checks = []


def checked_evaluate(model, train_pos, test_pos, top_k):
    users = [u for u, positives in test_pos.items() if positives]
    indices = train.np.linspace(0, len(users) - 1, min(128, len(users)), dtype=int) if users else []
    subset = {users[i]: test_pos[users[i]] for i in indices}
    started = time.monotonic()
    expected = reference_evaluate(model, train_pos, subset, top_k)
    reference_seconds = time.monotonic() - started
    started = time.monotonic()
    actual = optimized_evaluate(model, train_pos, subset, top_k)
    sample_seconds = time.monotonic() - started
    train.np.testing.assert_allclose(actual, expected, rtol=0, atol=1e-12)
    # Preserve the trained parameters for independent checks without retraining.
    output = Path(train.OUTPUT_DIR)
    train.np.savez(output / 'validation-model.npz', U=model.U, I=model.I, bu=model.bu, bi=model.bi)
    started = time.monotonic()
    result = optimized_evaluate(model, train_pos, test_pos, top_k)
    full_seconds = time.monotonic() - started
    checks.append({'sample_users': len(subset), 'total_test_users': len(users),
                   'reference_sample_metrics': [float(x) for x in expected],
                   'optimized_sample_metrics': [float(x) for x in actual],
                   'reference_sample_seconds': reference_seconds,
                   'optimized_sample_seconds': sample_seconds,
                   'full_evaluation_seconds': full_seconds,
                   'full_metrics': [float(x) for x in result]})
    (output / 'evaluation-checks.json').write_text(json.dumps(checks, indent=2))
    print('EVALUATION_CHECK=' + json.dumps(checks[-1]), flush=True)
    return result


if __name__ == '__main__':
    train.evaluate = checked_evaluate
    train.main()
