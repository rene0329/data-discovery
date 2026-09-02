# 公网 IPv4 离线归属地表

`ip2region_v4.xdb` 来自 [ip2region](https://github.com/lionsoul2014/ip2region)，
固定上游提交 `c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1`，下载日期 2026-09-02。
原始二进制大小 11,122,036 字节，未经修改；许可原文见 `LICENSE.ip2region`。

- [固定版本数据](https://raw.githubusercontent.com/lionsoul2014/ip2region/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/data/ip2region_v4.xdb)
- [字段说明](https://github.com/lionsoul2014/ip2region/blob/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/README_zh.md)
- [Java 查询 API](https://github.com/lionsoul2014/ip2region/blob/c1a1fc7d5941760db3f8431dc05c48cf7f0e30a1/binding/java/README_zh.md)

配套 `org.lionsoul:ip2region:3.3.7`，表记录格式为
`国家|省份|城市|ISP|国家代码`。只将前三项作为位置，去掉空值、`0` 和重复项；
不使用 ISP 或国家代码作为城市，不根据内网 IP、节点名称或部署标签推断位置。

后端启动时将表加载到内存，使用并发安全查询服务。拓扑每次读取当前 `external_ip`
查表，响应的 `publicIpLocation.ip` 始终附带被查询地址。不会向第三方发送节点信息，
不需要数据库迁移；缺少公网 IP、无匹配记录和表不可用有不同状态。
IP 归属地代表公网出口的位置，不能保证等于机器实际机房位置。

默认从 JAR 的 classpath 加载，构建时一并打包。可通过
`--node-geolocation.database=file:/opt/geoip/ip2region_v4.xdb` 指定维护者更新的表；
替换数据后重启后端，并运行 `PublicIpLocationServiceTest` 核对字段格式和已知地址。
不在请求过程中下载或刷新表。

本次改动需重新发布前后端生效；公网 IP 探测器无需修改或重发。
