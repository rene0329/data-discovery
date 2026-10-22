import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from computation_terms import computation_text


class ComputationTermsTest(unittest.TestCase):
    def test_legacy_log_and_storage(self):
        source = '分布式任务 分布式执行 分布式数据移动总耗时 集中式耗时 分布式存储 分布式协同缓存'
        expected = '原位计算任务 原位执行 原位计算数据移动总耗时 集中式计算耗时 分布式存储 分布式协同缓存'
        self.assertEqual(expected, computation_text(source))
        self.assertEqual(expected, computation_text(expected))
