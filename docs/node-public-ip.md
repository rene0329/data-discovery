# 节点公网出口 IP 探测

网络结构悬浮框分开显示内网 IP、公网 IP。公网 IP 由每个节点上的
`network-probe`（`hostNetwork: true`）查询，不在浏览器或中央后端机器上统一查询。

## 数据流

1. 探针启动约 10 秒后，向 `https://api-ipv4.ip.sb/ip` 发出带自定义 User-Agent 的 HTTPS GET，
   相当于在节点上执行 `curl -4 --noproxy '*' -A topic4-network-probe/1.0 https://api-ipv4.ip.sb/ip`。
   查询客户端不使用 HTTP 代理，连接/读取超时分别为 3/5 秒。
2. 默认每 10 分钟再次查询，频率不受浏览器的每秒刷新影响。
3. 校验返回值后，向 `POST /api/network/nodes/public-ip` 上报
   `clusterId`、`nodeName`、`k8sUid`、`publicIp`。
4. 后端按集群、K8s UID、节点名称匹配已注册且未删除的节点，仅更新 `external_ip`。
   不注册或启用节点，不修改链路、调度状态或内网地址。
5. `/common/networkTopology` 和 `/api/v1/nodes` 返回该地址。UI 优先采用刷新更快的拓扑结果。

上报接口沿用现有网络指标采集的内部服务通信方式，应与 `/api/network/metrics/batch`
一起限制在可信集群网络中；本次没有扩大探针的 Kubernetes RBAC 权限。

查询失败、返回错误页或非公网 IPv4 时不写入空值，保留上次成功值；首次未成功时
界面显示“未获取”。Kubernetes 未提供 ExternalIP 时，节点同步不会清空已探测值。
保留值代表上次成功结果，不代表当前网络一定可用。

这是公网**出口** IPv4：共享 NAT 时多个节点可以相同，不保证该地址可从公网直接入站访问，
也不根据该 IP 猜测物理机房位置。IPv6 不在本次探测范围。

接口说明：[IP.SB API](https://ip.sb/api/)。

## 配置与发布

- `LOCAL_CLUSTER_ID`：必须匹配后端注册记录的集群 ID，当前为 `in-cluster-default`。
- `LOCAL_NODE_NAME`：保留 Downward API 的 `spec.nodeName`。
- `PUBLIC_IP_LOOKUP_URL`：默认 IPv4-only IP.SB endpoint。
- `PUBLIC_IP_INTERVAL_MS`：默认 `600000`。
- `PUBLIC_IP_INITIAL_DELAY_MS`：默认 `10000`。
- `CENTRAL_PUBLIC_IP_URL`：新增上报接口的集群内地址。

无数据库结构迁移。发布顺序为后端 → 网络探针 → 前端；探针配置见
`network-probe-daemonset-service/k8s.yaml`。本次实现和实测没有发布新镜像、写入生产节点 IP、
或修复集群 DNS。

## 2026-09-02 实测（UTC+8）

在六个现有探针 Pod 内通过同一 IPv4 HTTPS endpoint 查询：

| 节点 | 结果 |
| --- | --- |
| master-88 | 42.228.13.134 |
| master-89 | 171.8.254.45 |
| master-90 | 171.8.254.48 |
| alihz | 121.43.57.204 |
| alibj | 182.92.121.10 |
| alish | DNS 解析失败：`bad address 'api-ipv4.ip.sb'`，未取得地址 |

以上为验证快照，不是前端硬编码数据。上海节点需要恢复探针 Pod 的 DNS 解析能力后才能上报。
