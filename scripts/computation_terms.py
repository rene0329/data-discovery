"""Display legacy computation terminology without changing raw evidence."""
import re
import sys


def computation_text(value):
    value = str(value)
    for old, new in [('分布式调度方案', '原位调度方案'), ('分布式执行', '原位执行'),
                     ('分布式任务', '原位计算任务'), ('分布式计算', '原位计算')]:
        value = value.replace(old, new)
    value = re.sub(r'分布式(?=数据移动|总搬运|搬运|耗时|计时|节点|路径|参考节点|与集中式|／集中式)', '原位计算', value)
    value = value.replace('任务类型=分布式', '任务类型=原位计算')
    return re.sub(r'集中式(?!计算|调度方案)', '集中式计算', value)


if __name__ == '__main__':
    for line in sys.stdin:
        print(computation_text(line), end='', flush=True)
