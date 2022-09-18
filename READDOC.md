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

#### Docker compose
```shell
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

version: '3.8'
services:
  elasticsearch:
    image: elasticsearch:7.5.1
    container_name: elasticsearch
    ports:
      - "9200:9200"
    healthcheck:
      test: [ "CMD-SHELL", "curl --silent --fail localhost:9200/_cluster/health || exit 1" ]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s
    environment:
      - discovery.type=single-node
      - bootstrap.memory_lock=true
      - "ES_JAVA_OPTS=-Xms512m -Xmx1024m"
      - TZ=Asia/Shanghai
    ulimits:
      memlock:
        soft: -1
        hard: -1

  oap:
    image: apache/skywalking-oap-server:9.2.0
    container_name: oap
    depends_on:
      elasticsearch:
        condition: service_healthy
    links:
      - elasticsearch
    ports:
      - "11800:11800"
      - "12800:12800"
    healthcheck:
      test: [ "CMD-SHELL", "/skywalking/bin/swctl ch" ]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s
    environment:
      SW_STORAGE: elasticsearch
      SW_STORAGE_ES_CLUSTER_NODES: elasticsearch:9200
      SW_HEALTH_CHECKER: default
      SW_TELEMETRY: prometheus
      JAVA_OPTS: "-Xms512m -Xmx1024m"
      TZ: "Asia/Shanghai"

  ui:
    image: apache/skywalking-ui:9.2.0
    container_name: ui
    depends_on:
      oap:
        condition: service_healthy
    links:
      - oap
    ports:
      - "8080:8080"
    environment:
      SW_OAP_ADDRESS: http://oap:12800
      TZ: "Asia/Shanghai"
  
  mysql:
    image: daocloud.io/library/mysql:5.7.7
    container_name: mysql
    ports:
      - 3306:3306
    environment:
      - MYSQL_ROOT_PASSWORD=root
      - TZ=Asia/Shanghai
    volumes:
      - ~/develop/data/mysql/conf:/etc/mysql/conf.d
      - ~/develop/data/mysql/data:/var/lib/mysql
      
  mysql-service:
    image: prom/mysqld-exporter:v0.14.0
    ports:
      - 9104
    environment:
      - DATA_SOURCE_NAME=mysql_exporter:mysql_exporter@(mysql:3306)/
      - TZ=Asia/Shanghai
    depends_on:
      - mysql
  otel-collector:
    image: otel/opentelemetry-collector:0.50.0
    command: [ "--config=/etc/otel-collector-config.yaml" ]
    volumes:
      - ~/develop/data/apm/otel-collector-config.yaml:/etc/otel-collector-config.yaml
    expose:
      - 55678
    depends_on:
      oap:
        condition: service_healthy    

```


```shell
create user 'mysql_exporter'@'%' identified by 'mysql_exporter';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'mysql_exporter'@'%' WITH MAX_USER_CONNECTIONS 3;
flush privileges;
```


