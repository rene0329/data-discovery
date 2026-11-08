# UCI HIGGS MLP 训练镜像

用于系统计算任务和训练日志展示。使用 CPU PyTorch 2.2.2，网络为 28 → 128 → 64 → 1，ReLU 激活，Adam 优化器，BCEWithLogitsLoss 损失函数。

默认执行精简演示：按文件顺序读取前 500,000 条样本、训练 10 轮，跳过模型评估。支持标签加 28 个特征的无表头 CSV，也支持 gzip 文件，不完整解压文件。读取达到样本上限后停止，不声称扫描了全部 1,100 万条样本或验证了 gzip 文件尾部。全文件完整性由系统已有的数据副本校验流程负责。

日志包括数据文件大小、本次读取数量、标准化和初始化耗时、每轮 loss、训练准确率及耗时、参数更新确认和任务完成信息。训练准确率仅来自所选训练样本，不代表测试集效果。结果写入 `$OUTPUT_DIR/metrics.log` 和 `summary.json`，不保存大模型检查点。

## 构建和测试

本地可运行标准库数据读取测试；缺少 PyTorch 时训练集成测试会明确跳过。镜像构建阶段会在固定基础镜像中运行全部测试，包括真实 10 轮训练和参数更新验证。

```bash
python3 -B -m unittest discover -s training-images/tabular-mlp/tests -v
```

共享构建工具尚无训练镜像目标，因此在 master-40 的干净 GitHub checkout 使用本目录脚本。脚本拉取 main、使用完整提交 SHA 标记镜像、执行 Dockerfile 内测试，不部署后端。

```bash
source /data/topic4-build/bin/env.sh
PUSH=1 training-images/tabular-mlp/build.sh
```

基础镜像固定为已经验证的内部 GRU CPU 运行时摘要，仅复用其 PyTorch 环境，不使用 GRU 模型，不在构建时联网安装依赖。

## 系统运行镜像配置

- taskType: `classification`
- modelType: `mlp`
- command: `["python", "/app/train.py"]`
- argsTemplate: `["--epochs", "10", "--max-samples", "500000", "--batch-size", "256", "--threads", "2"]`
- dataPathTemplate: `/data/tabular/{dataset}`
- defaultResources: CPU 2、内存 2 GiB、GPU 0

必须显式保留 `--epochs 10`：后端会注入统一的 `N_EPOCHS`，命令行参数优先于此环境变量。仅修改镜像 CMD 无法覆盖后端指定的容器命令。输入通过 `DATA_PATH` 读取，不把 2.8 GB 数据打包到镜像。
