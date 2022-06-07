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
docker pull 
```
