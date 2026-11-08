# UCI HIGGS MLP 训练镜像

使用 CPU PyTorch 2.2.2，网络为 28 → 128 → 64 → 1，ReLU 激活，Adam 优化器，BCEWithLogitsLoss 损失函数。

默认全量训练 1 轮：`--epochs 1 --max-samples 0`。读取 gzip CSV 至文件结束，包含数据尾部完整性检查，每条样本参与一次训练。支持标签加 28 个特征的无表头 CSV，支持 gzip 压缩。每次最多保留一块 8,192 条样本，不完整解压文件或一次性加载全量数据。

全量模式使用首块样本计算并固定标准化参数，后续所有块复用同一参数；首块也完整参与训练。按文件顺序读取，各块内部随机洗牌，不执行全局洗牌。这样只需一次文件扫描即可完成一轮训练，内存占用不随数据总量增长。

每处理约 10 万条或经过 5 秒输出训练进度，包括已训练样本数、批次数、累计 loss、训练准确率和耗时；结束时输出实际全量样本数、轮数、数据读取解析时间、参数更新时间及总耗时。跳过模型评估，训练准确率仅来自训练过程，不代表测试集效果。结果写入 `$OUTPUT_DIR/metrics.log` 和 `summary.json`，不保存大模型检查点。

保留有限样本模式，例如 `--epochs 10 --max-samples 500000`。该模式将指定数量的样本载入内存，不适合全量数据；全量必须使用 `--max-samples 0`。

## 构建和测试

```bash
python3 -B -m unittest discover -s training-images/tabular-mlp/tests -v
```

缺少 PyTorch 时，本地训练集成测试明确跳过。镜像构建阶段在固定基础镜像中运行全部测试，包括真实训练、全量计数、末尾不足一块的数据和 gzip 尾部校验。

共享构建工具尚无训练镜像目标，在 master-40 的干净 GitHub checkout 使用本目录脚本。脚本拉取 main、使用完整提交 SHA 标记镜像、执行 Dockerfile 内测试，不部署后端。

```bash
source /data/topic4-build/bin/env.sh
PUSH=1 training-images/tabular-mlp/build.sh
```

基础镜像固定为已经验证的内部 GRU CPU 运行时摘要，仅复用其 PyTorch 环境，不使用 GRU 模型，不在构建时联网安装依赖。

## 系统运行镜像配置

- taskType: `classification`
- modelType: `mlp`
- command: `["python", "/app/train.py"]`
- argsTemplate: `["--epochs", "1", "--max-samples", "0", "--chunk-size", "8192", "--batch-size", "256", "--threads", "2"]`
- dataPathTemplate: `/data/tabular/{dataset}`
- defaultResources: CPU 2、内存 2 GiB、GPU 0

显式命令行参数优先于后端注入的 `N_EPOCHS`。输入通过 `DATA_PATH` 读取，不把 2.8 GB 数据打包到镜像。
