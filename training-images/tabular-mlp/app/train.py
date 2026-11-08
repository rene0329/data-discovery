"""Real HIGGS MLP training with bounded-memory full-dataset streaming."""
import argparse
import csv
import gzip
import itertools
import json
import logging
import math
import os
from pathlib import Path
import sys
import time

log = logging.getLogger('higgs-mlp')


def positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError('must be positive')
    return number


def nonnegative(value):
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError('must be nonnegative; 0 means all samples')
    return number


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-path', default=os.getenv('DATA_PATH', '/data/tabular/higgs.csv.gz'))
    # Explicit runtime args take precedence over the backend-wide N_EPOCHS setting.
    parser.add_argument('--epochs', type=positive, default=os.getenv('N_EPOCHS', '1'))
    parser.add_argument('--max-samples', type=nonnegative, default='0', help='0: full dataset')
    parser.add_argument('--batch-size', type=positive, default='256')
    parser.add_argument('--threads', type=positive, default='2')
    parser.add_argument('--chunk-size', type=positive, default='8192')
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--output-dir', default=os.getenv('OUTPUT_DIR', '/data/output'))
    return parser.parse_args(argv)


def iter_rows(path, limit=None):
    """Validate rows lazily; a full scan also checks the gzip trailer."""
    if limit is not None and limit <= 0:
        raise ValueError('sample limit must be positive')
    with open(path, 'rb') as probe:
        compressed = probe.read(2) == b'\x1f\x8b'
    open_text = gzip.open if compressed else open
    with open_text(path, 'rt', encoding='utf-8', newline='') as stream:
        for line, row in enumerate(itertools.islice(csv.reader(stream), limit), 1):
            if len(row) != 29:
                raise ValueError('row {}: expected label and 28 features, got {} columns'.format(line, len(row)))
            try:
                values = [float(value) for value in row]
            except ValueError as error:
                raise ValueError('row {}: invalid numeric value'.format(line)) from error
            if not all(math.isfinite(value) and abs(value) <= 3.402823466e38 for value in values):
                raise ValueError('row {}: values must be finite float32 numbers'.format(line))
            if values[0] not in (0., 1.):
                raise ValueError('row {}: label must be 0 or 1'.format(line))
            yield values[1:], values[0]



def read_sample(path, limit):
    features, labels = [], []
    for row, label in iter_rows(path, limit):
        features.append(row)
        labels.append(label)
    if not labels:
        raise ValueError('dataset is empty')
    return features, labels


def iter_chunks(path, chunk_size):
    if chunk_size <= 0:
        raise ValueError('chunk size must be positive')
    rows = iter_rows(path)
    while True:
        chunk = list(itertools.islice(rows, chunk_size))
        if not chunk:
            return
        yield [row for row, _ in chunk], [label for _, label in chunk]


def train_full(config):
    started = time.monotonic()
    path = Path(config.data_path)
    log.info('【运行配置】数据集=UCI HIGGS 模型=MLP 模式=全量流式训练 epochs=%d chunk_size=%d batch_size=%d',
             config.epochs,config.chunk_size,config.batch_size)
    log.info('【数据读取】文件=%s 文件大小=%d字节；读取至文件结束，每条样本每轮训练一次',path,path.stat().st_size)
    import torch
    from torch import nn
    torch.set_num_threads(config.threads)
    torch.manual_seed(config.seed)
    model = nn.Sequential(nn.Linear(28,128),nn.ReLU(),nn.Linear(128,64),nn.ReLU(),nn.Linear(64,1))
    optimizer = torch.optim.Adam(model.parameters(),lr=1e-3)
    criterion = nn.BCEWithLogitsLoss()
    before = model[0].weight.detach().clone()
    history = []
    mean, scale = None, None
    total_read_seconds, total_update_seconds = 0., 0.
    log.info('【训练开始】网络=28→128→64→1；按文件顺序读取、块内洗牌；跳过模型评估')
    training_started = time.monotonic()
    model.train()
    for epoch in range(1,config.epochs+1):
        epoch_started=time.monotonic()
        chunks=iter_chunks(path,config.chunk_size)
        count, batches, correct, positives = 0, 0, 0, 0
        total_loss=0.
        last_count,last_log=0,time.monotonic()
        while True:
            read_started=time.monotonic()
            try:
                features,labels=next(chunks)
            except StopIteration:
                total_read_seconds+=time.monotonic()-read_started
                break
            total_read_seconds+=time.monotonic()-read_started
            update_started=time.monotonic()
            x=torch.tensor(features,dtype=torch.float32)
            y=torch.tensor(labels,dtype=torch.float32)
            del features,labels
            if mean is None:
                mean=x.mean(0)
                scale=x.std(0,unbiased=False).clamp_min(1e-6)
                log.info('【数据准备】使用首块%d条样本固定标准化参数；首块仍参与训练，后续各块复用同一参数',len(y))
            x=(x-mean)/scale
            if not torch.isfinite(x).all():
                raise ValueError('standardization produced non-finite features')
            for indices in torch.randperm(len(y)).split(config.batch_size):
                optimizer.zero_grad()
                logits=model(x[indices]).squeeze(1)
                loss=criterion(logits,y[indices])
                if not torch.isfinite(loss):
                    raise ValueError('training loss is not finite')
                loss.backward()
                optimizer.step()
                total_loss+=loss.item()*len(indices)
                correct+=((logits.detach()>=0)==(y[indices]>=.5)).sum().item()
                batches+=1
            count+=len(y)
            positives+=int(y.sum().item())
            total_update_seconds+=time.monotonic()-update_started
            if count-last_count>=100000 or time.monotonic()-last_log>=5:
                log.info('【训练进度】Epoch %d/%d | 已训练=%d条 | batches=%d | loss=%.6f | train_accuracy=%.4f | 已耗时=%.1fs',
                         epoch,config.epochs,count,batches,total_loss/count,correct/count,time.monotonic()-epoch_started)
                last_count,last_log=count,time.monotonic()
        if count==0:
            raise ValueError('dataset is empty')
        if history and count!=history[0]['samples']:
            raise ValueError('sample count changed between epochs')
        item={'epoch':epoch,'samples':count,'batches':batches,'positive_samples':positives,
              'loss':total_loss/count,'train_accuracy':correct/count,'seconds':time.monotonic()-epoch_started}
        history.append(item)
        log.info('【轮次完成】Epoch %d/%d | 全量样本=%d | loss=%.6f | train_accuracy=%.4f | 耗时=%.3fs',
                 epoch,config.epochs,count,item['loss'],item['train_accuracy'],item['seconds'])
    changed=not torch.equal(before,model[0].weight.detach())
    if not changed:
        raise RuntimeError('model parameters did not change')
    result={'dataset':'UCI HIGGS','model':'mlp','mode':'full-streaming','sampling':'all_rows',
            'samples':history[-1]['samples'],'features':28,'epochs':config.epochs,'chunk_size':config.chunk_size,
            'standardization':'fixed_first_chunk','shuffle':'within_chunk',
            'evaluation':'skipped','parameters_changed':changed,'history':history,
            'read_seconds':total_read_seconds,'update_seconds':total_update_seconds,
            'train_seconds':time.monotonic()-training_started,'seed':config.seed,'torch_version':torch.__version__}
    output=Path(config.output_dir)
    output.mkdir(parents=True,exist_ok=True)
    (output/'metrics.log').write_text('task_type=classification\nmodel_type=mlp\nmode=full-streaming\n'
        'evaluation=skipped\nn_train={}\nepochs={}\nloss={:.6f}\ntrain_accuracy={:.4f}\n'.format(
            result['samples'],config.epochs,history[-1]['loss'],history[-1]['train_accuracy']),encoding='utf-8')
    result['process_seconds']=time.monotonic()-started
    (output/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    log.info('【评估跳过】未计算验证集或测试集指标；train_accuracy是训练过程的累计准确率')
    log.info('【训练完成】全量样本=%d 已完成=%d/%d轮 累计样本处理=%d 参数已更新=%s 读取解析=%.3fs 参数更新=%.3fs 总耗时=%.3fs',
             result['samples'],config.epochs,config.epochs,result['samples']*config.epochs,changed,
             total_read_seconds,total_update_seconds,result['process_seconds'])
    return result


def train(config):
    if config.max_samples == 0:
        return train_full(config)
    started = time.monotonic()
    path = Path(config.data_path)
    log.info('【运行配置】数据集=UCI HIGGS 模型=MLP 模式=精简演示 epochs=%d max_samples=%d batch_size=%d',
             config.epochs, config.max_samples, config.batch_size)
    log.info('【数据读取】文件=%s 文件大小=%d字节；顺序读取前%d条，不完整解压文件',
             path, path.stat().st_size, config.max_samples)
    read_started = time.monotonic()
    features, labels = read_sample(path, config.max_samples)
    read_seconds = time.monotonic() - read_started
    log.info('【读取完成】本次样本=%d 特征数=28 正样本=%d 负样本=%d 读取耗时=%.3fs；未扫描全量样本',
             len(labels), sum(labels), len(labels)-sum(labels), read_seconds)

    import torch
    from torch import nn
    torch.set_num_threads(config.threads)
    torch.manual_seed(config.seed)
    x = torch.tensor(features, dtype=torch.float32)
    y = torch.tensor(labels, dtype=torch.float32)
    del features, labels  # Release Python row objects before training the 500k sample.
    mean = x.mean(0)
    scale = x.std(0, unbiased=False).clamp_min(1e-6)
    x = (x-mean)/scale
    if not torch.isfinite(x).all():
        raise ValueError('standardization produced non-finite features')
    model = nn.Sequential(nn.Linear(28, 128), nn.ReLU(), nn.Linear(128, 64),
                          nn.ReLU(), nn.Linear(64, 1))
    optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
    criterion = nn.BCEWithLogitsLoss()
    before = model[0].weight.detach().clone()
    prepare_seconds = time.monotonic()-started
    log.info('【数据准备】标准化完成 网络=28→128→64→1 参数量=%d 准备耗时=%.3fs',
             sum(p.numel() for p in model.parameters()), prepare_seconds)
    log.info('【训练开始】共%d轮，每轮%d个批次；准确率为训练样本上的准确率',
             config.epochs, math.ceil(len(y)/config.batch_size))
    training_started = time.monotonic()
    history = []
    model.train()
    for epoch in range(1, config.epochs+1):
        epoch_started = time.monotonic()
        permutation = torch.randperm(len(y))
        total_loss, correct = 0., 0
        for indices in permutation.split(config.batch_size):
            optimizer.zero_grad()
            logits = model(x[indices]).squeeze(1)
            loss = criterion(logits, y[indices])
            if not torch.isfinite(loss):
                raise ValueError('training loss is not finite')
            loss.backward()
            optimizer.step()
            total_loss += loss.item()*len(indices)
            correct += ((logits.detach() >= 0) == (y[indices] >= .5)).sum().item()
        item = {'epoch': epoch, 'loss': total_loss/len(y), 'train_accuracy': correct/len(y),
                'seconds': time.monotonic()-epoch_started}
        history.append(item)
        log.info('【训练进度】Epoch %2d/%d | loss=%.6f | train_accuracy=%.4f | 本轮耗时=%.3fs',
                 epoch, config.epochs, item['loss'], item['train_accuracy'], item['seconds'])
    training_seconds = time.monotonic()-training_started
    changed = not torch.equal(before, model[0].weight.detach())
    if not changed:
        raise RuntimeError('model parameters did not change')
    result = {'dataset': 'UCI HIGGS', 'model': 'mlp', 'mode': 'quick-demo',
              'sampling': 'first_rows', 'samples': len(y), 'features': 28,
              'epochs': config.epochs, 'evaluation': 'skipped', 'parameters_changed': changed,
              'read_seconds': read_seconds, 'prepare_seconds': prepare_seconds,
              'train_seconds': training_seconds, 'history': history,
              'seed': config.seed, 'torch_version': torch.__version__}
    output = Path(config.output_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output/'metrics.log').write_text('task_type=classification\nmodel_type=mlp\nmode=quick-demo\n'
        'evaluation=skipped\nn_train={}\nepochs={}\nloss={:.6f}\ntrain_accuracy={:.4f}\n'.format(
            len(y),config.epochs,history[-1]['loss'],history[-1]['train_accuracy']), encoding='utf-8')
    result['process_seconds'] = time.monotonic()-started
    (output/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    log.info('【评估跳过】精简演示不计算验证集或测试集指标；训练准确率不代表泛化效果')
    log.info('【训练完成】数据集=UCI HIGGS 已完成=%d/%d轮 累计样本处理=%d 参数已更新=%s 训练耗时=%.3fs 总耗时=%.3fs',
             config.epochs,config.epochs,len(y)*config.epochs,changed,training_seconds,result['process_seconds'])
    log.info('【结果输出】%s',output/'summary.json')
    return result


def main(argv=None):
    logging.basicConfig(level=logging.INFO,format='%(asctime)s [%(levelname)s] %(message)s',stream=sys.stdout)
    config = arguments(argv)
    try:
        train(config)
    except Exception:
        log.exception('【训练失败】数据集=UCI HIGGS 模型=MLP')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
