# 固定逻辑拓扑与多跳调度

当前实现固定业务系统使用的连接关系。SSH TUN、宿主机路由、Flannel 和实际数据传输路径不变。

## 数据库条目

新增 `logical_topology_edge`，作为前端拓扑和调度的连接来源。`edge_management` 中的历史探测记录保留，不再参与这些业务入口。

| source_node_name | target_node_name |
| --- | --- |
| master-88 | master-89 |
| master-88 | master-90 |
| master-89 | master-90 |
| alihz | master-88 |
| alish | master-88 |
| alibj | master-90 |

每行表示一条无向边，节点名按字典序保存。查询时关联未注销的 `node_management` 记录得到实际 ID，较小 ID 为 `source_id`。节点尚未注册时保留配置，注册后对应连线自动可见。启用节点和网络探测均不会创建新的逻辑连接。新增接入关系需显式修改配置表。

新行初始 `bandwidth = NULL`、`latency = NULL`、`status = UNKNOWN`、`measurement_time = NULL`，不把旧表中没有可靠测量时间的历史值当成新测量。

## 指标和可用性

探测上报只更新已配置的节点对，反向上报更新同一行。完整成功结果更新带宽（Mbps）、延迟（ms）、状态 `active` 和服务器 UTC 接收时间；失败或不完整结果保留原指标并标记 `inactive`。不在配置表中的探测结果直接忽略。

超过 `app.network-topology.stale-after-seconds` 未更新的成功记录，在读取时标记 `STALE`。默认 1800 秒，覆盖目前 600 秒的探测周期。失败、未知、过期的边继续出现在管理视图中，但不参加调度。节点停用、离线或观测过期时，也不能作为路径的起点、终点或中间节点。

## 调度规则

路径选择使用累计延迟最小的可用路径；累计延迟相同时优先跳数较少的路径。路径带宽取所选路径各边带宽的最小值。该累计延迟是逻辑调度成本；探测仍在实际网络上执行，实际传输耗时仍由现有任务流程测量。

例如杭州到北京：`alihz → master-88 → master-90 → alibj`。若 88—90 失效但其余中心链路可用，则可经过 `master-89` 绕行。杭州—88 失效时，杭州不能参与跨节点传输；健康节点的本地任务仍可执行。

以下入口统一受逻辑拓扑约束：

- `/common/networkTopology`、`/common/links`、`/api/network/allMetrics` 返回固定连接及动态状态。
- 自动计算节点选择使用整条路径的延迟和瓶颈带宽。
- 指定目标节点的调度也必须存在可用路径。
- 外部调度方案提交时校验路径；执行前再次校验，避免排队期间链路失效后仍开始复制。
- 存储分配及逻辑备份只选择当前可达的节点，中心度依据配置的无向连接计算。

## 发布顺序

1. 在业务数据库执行 `practice-server/src/main/resources/db/migration/V20260902_1__fixed_logical_topology.sql`，创建表并写入上述 6 条配置。项目当前未配置自动执行该迁移。
2. 发布包含本次修改的 `practice-server`，随后发布 `network-probe-daemonset-service`。新后端不再读取旧表构造拓扑。
3. 等待成功探测更新六条边；初始未知的边仅显示，不参与跨节点调度。旧版探测器无法上报双指标都失败的情况，更新探测器前由 1800 秒过期规则兜底。
4. 用下方查询检查配置与指标，并验证拓扑接口。整个过程不需要修改隧道、路由或 CNI。

```sql
SELECT edge_id, source_node_name, target_node_name,
       bandwidth, latency, status, measurement_time
FROM logical_topology_edge
ORDER BY edge_id;
```

回退时恢复之前的后端和探测器版本即可重新使用原 `edge_management`。新增表可以保留；回退不会把逻辑路径强加到实际网络。

## 验证

覆盖固定节点对更新、越界探测忽略、失败保留及恢复、过期指标、多跳路径、中心绕行、叶子断联、中间节点不可用、自动和指定目标调度、外部方案拒绝、启用节点不建边以及 MyBatis 映射加载。

```sh
mvn -pl practice-server,network-probe-daemonset-service -am \
  -Dtest=NetworkTopologyServiceTest,NetworkMetricsServiceTest,K8sJobFactoryTopologyTest,LogicalTopologyMapperTest,CommonControllerTopologyTest,CommonContractTest,NodeRegistrationServiceTest,SchedulingServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
