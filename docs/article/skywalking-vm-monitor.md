SkyWalking 8.4 版本开始支持监控主机， 用户可以轻松从仪表板上检测可能的问题，例如当 CPU 使用过载、内存或磁盘空间不足或者当网络状态不健康时等。
SkyWalking 利用 Prometheus 和 OpenTelemetry 收集主机的 metrics 数据。

处理流程如下：
1.Prometheus Node Exporter 从主机收集 metrics 数据.
2.OpenTelemetry Collector 通过 Prometheus Receiver 从 Node Exporters 抓取 metrics 数据, 然后将 metrics 推送的到 SkyWalking OAP Server.
3.SkyWalking OAP Server 通过 MAL 引擎去分析、计算、聚合和存储，处理规则位于 /config/otel-oc-rules/vm.yaml 文件.
4.用户可以通过 SkyWalking WebUI dashboard 查看监控数据。

