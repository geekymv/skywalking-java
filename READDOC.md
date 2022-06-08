[SkyWalking 调试环境搭建](https://www.jianshu.com/p/01b72a09444b)
```text
File -> New -> Module from Existing Sources -> 选择需要导入的项目
-> Import module from external model -> Maven
```
#### SkyWalking Docker
- ElasticSearch7
```shell
https://hub.docker.com/_/elasticsearch

docker pull elasticsearch:7.5.1

docker run -itd --name es7 \
-p 9200:9200 -p 9300:9300 \
-e "discovery.type=single-node" elasticsearch:7.5.1
```

- SkyWalking
```shell
https://hub.docker.com/r/apache/skywalking-oap-server

docker pull apache/skywalking-oap-server:8.7.0-es7

docker run -itd --name oap \
-p 12800:12800 -p 11800:11800 \
--link es7:es7 \
-e SW_STORAGE=elasticsearch7 \
-e SW_STORAGE_ES_CLUSTER_NODES=es7:9200 \
apache/skywalking-oap-server:8.7.0-es7


```
http://t.zoukankan.com/fsckzy-p-15796933.html
apache/skywalking-oap-server:8.8.1
会报错 no provider found for module storage

- SkyWalking UI
```shell
https://hub.docker.com/r/apache/skywalking-ui

docker pull apache/skywalking-ui:8.8.1

docker run -itd --name oap-ui \
-p 9090:8080 \
--link oap:oap \
-e SW_OAP_ADDRESS=http://oap:12800 \
apache/skywalking-ui:8.8.1

```

