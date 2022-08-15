
Java类加载器双亲委托机制可能大家平常或多或少的都了解一些，面试也可能被问到，学习了类加载器的理论，除了面试的时候可能被问到，也有可能面试官根本不会去问，
花这么多时间去学这个东西到底有啥用？笔者之前也有这样的困惑，直到后来看到了SkyWalking Java agent 中的自定义类加载器，才发现原来类Java自定义类加载器这么有用！！！。
不知道类加载器的小伙伴可以去看看《深入理解Java虚拟机》这本书，下面我也会简单介绍下类加载器，

我们知道 Java 编译器将我们编写的 .java源代码编译成 .class字节码文件，这些 .class文件描述的各类信息最终都需要加载到虚拟机中才能被运行和使用，
类的加载过程包括 加载->连接和初始化





看了这么多理论大家可能还是不知道怎么用，那么我们可以看看一些开源框架都是怎么使用的，



毫不夸张的说，自定义的类加载器AgentClassLoader 是 SkyWalking Java agent 非常核心的组成部分，通过它加载插件并实例化插件

首先说下背景，SkyWalking 是什么以及它用来解决什么问题，
为了不和我们的业务代码耦合，SkyWalking 通过使用 Java agent 技术实现无代码侵入的在我们的代码中进行埋点，从我们的业务代码中收集链路数据，发送给 SkyWalking 后端去分析、展示、告警等。

skywalking agent -> skywalking backend

SkyWalking Java agent 目录结构如下：
```
+-- agent
    +-- activations
         apm-toolkit-log4j-1.x-activation.jar
         apm-toolkit-log4j-2.x-activation.jar
         apm-toolkit-logback-1.x-activation.jar
         ...
    +-- config
         agent.config  
    +-- plugins
         apm-dubbo-plugin.jar
         apm-feign-default-http-9.x.jar
         apm-httpClient-4.x-plugin.jar
         .....
    +-- optional-plugins
         apm-gson-2.x-plugin.jar
         .....
    +-- bootstrap-plugins
         jdk-http-plugin.jar
         .....
    +-- logs
    skywalking-agent.jar
```
比如我们有个使用 Spring Boot 开发的应用，可以通过下面的形式配置 skywalking-agent.jar，然后在 config/agent.config 中指定 SkyWalking 后端接收数据的地址就可以了。
 ```shell
 java -javaagent:/path/to/skywalking-agent/skywalking-agent.jar -jar yourApp.jar
 ```
插件都放在 agent.jar 所在目录下的 plugins 和 activations 目录下，这样如果我们不需要某个插件，从目录中将jar包移出去就可以了，不用改一行代码。

























