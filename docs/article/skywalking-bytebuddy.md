**基于 SkyWalking Java Agent 8.8.0 版本**

通过之前文章的学习我们了解到 SkyWalking Java Agent 插件加载机制和插件体系。
- [Apache SkyWalking Java Agent 01-源码编译](https://juejin.cn/post/7108340744967946270)
- [Apache SkyWalking Java Agent 02-日志组件分析](https://juejin.cn/post/7047329063135871013)
- [Apache SkyWalking Java Agent 03-配置初始化流程分析](https://juejin.cn/post/7050487296243531784)  
- [Apache SkyWalking Java Agent 04-插件加载机制（上）](https://juejin.cn/post/7055481726331519006)
- [Apache SkyWalking Java Agent 05-插件加载机制（下）](https://juejin.cn/post/7108638682214563870)
- [Apache SkyWalking Java Agent 06-插件定义体系](https://juejin.cn/post/7109032881938235423)

本篇文章我们继续分析 SkyWalking Java Agent 源码，先回到插件加载部分的代码。
```java
pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());
```
通过 `PluginBootstrap#loadPlugins` 我们完成了插件定义类的加载和实例化，然后将插件对象列表传入`PluginFinder`的构造方法，根据增强类的匹配规则对插件匹配做分类。
```java
/**
 * The <code>PluginFinder</code> represents a finder , which assist to find the one from the given {@link
 * AbstractClassEnhancePluginDefine} list.
 */
public class PluginFinder {
    private final Map<String, LinkedList<AbstractClassEnhancePluginDefine>> nameMatchDefine = new HashMap<String, LinkedList<AbstractClassEnhancePluginDefine>>();
    private final List<AbstractClassEnhancePluginDefine> signatureMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();
    private final List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();

    public PluginFinder(List<AbstractClassEnhancePluginDefine> plugins) {
        // 对插件匹配分类
        for (AbstractClassEnhancePluginDefine plugin : plugins) {
            ClassMatch match = plugin.enhanceClass();

            if (match == null) {
                continue;
            }

            if (match instanceof NameMatch) {
                // 类名匹配
                NameMatch nameMatch = (NameMatch) match;
                // 根据目标类（比如 tomcat 中的 org.apache.catalina.core.StandardHostValve 类 ）找到对应的增强插件定义类
                LinkedList<AbstractClassEnhancePluginDefine> pluginDefines = nameMatchDefine.get(nameMatch.getClassName());
                if (pluginDefines == null) {
                    pluginDefines = new LinkedList<AbstractClassEnhancePluginDefine>();
                    nameMatchDefine.put(nameMatch.getClassName(), pluginDefines);
                }
                pluginDefines.add(plugin);
            } else {
                // 间接匹配
                signatureMatchDefine.add(plugin);
            }

            if (plugin.isBootstrapInstrumentation()) {
                // JDK 内置类
                bootstrapClassMatchDefine.add(plugin);
            }
        }
    }

    // 省略部分代码
    
}
```
主要是两种分类：
- nameMatchDefine 类名匹配；
- signatureMatchDefine 间接匹配；

如果是对JDK内置类的增强，则放入 bootstrapClassMatchDefine 中。

#### Byte Buddy

接下来我们进行到 [Byte Buddy](https://bytebuddy.net/) 部分的代码分析，Byte Buddy 是一个字节码生成和操作库，用于在Java应用程序运行时创建和修改类，而无需编译器的帮助。
```text
Byte Buddy is a code generation and manipulation library for creating and modifying Java classes during the runtime of a Java application and without the help of a compiler. Other than the code generation utilities that ship with the Java Class Library, Byte Buddy allows the creation of arbitrary classes and is not limited to implementing interfaces for the creation of runtime proxies. Furthermore, Byte Buddy offers a convenient API for changing classes either manually, using a Java agent or during a build.
```
更多内容可以访问 Byte Buddy 官网 https://bytebuddy.net/ 查看。

我们这里重点看下 SkyWalking 中是如何通过 Byte Buddy 实现插桩的核心代码

```java
/**
 * The main entrance of sky-walking agent, based on javaagent mechanism.
 */
public class SkyWalkingAgent {
    private static ILog LOGGER = LogManager.getLogger(SkyWalkingAgent.class);

    /**
     * Main entrance. Use byte-buddy transform to enhance all classes, which define in plugins.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) throws PluginException {
        final PluginFinder pluginFinder;
        try {
            SnifferConfigInitializer.initializeCoreConfig(agentArgs);
        } catch (Exception e) {
            // try to resolve a new logger, and use the new logger to write the error log here
            // 配置初始化过程可能会抛出异常（验证非空参数），这里为了使用新的 LogResolver 需要重新获取日志对象
            LogManager.getLogger(SkyWalkingAgent.class)
                    .error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        } finally {
            // refresh logger again after initialization finishes
            LOGGER = LogManager.getLogger(SkyWalkingAgent.class);
        }

        try {
            pluginFinder = new PluginFinder(new PluginBootstrap().loadPlugins());
        } catch (AgentPackageNotFoundException ape) {
            LOGGER.error(ape, "Locate agent.jar failure. Shutting down.");
            return;
        } catch (Exception e) {
            LOGGER.error(e, "SkyWalking agent initialized failure. Shutting down.");
            return;
        }

        final ByteBuddy byteBuddy = new ByteBuddy().with(TypeValidation.of(Config.Agent.IS_OPEN_DEBUGGING_CLASS));

        AgentBuilder agentBuilder = new AgentBuilder.Default(byteBuddy).ignore(
                nameStartsWith("net.bytebuddy.")
                        .or(nameStartsWith("org.slf4j."))
                        .or(nameStartsWith("org.groovy."))
                        .or(nameContains("javassist"))
                        .or(nameContains(".asm."))
                        .or(nameContains(".reflectasm."))
                        .or(nameStartsWith("sun.reflect"))
                        .or(allSkyWalkingAgentExcludeToolkit())
                        .or(ElementMatchers.isSynthetic()));

        // 省略部分代码

        agentBuilder.type(pluginFinder.buildMatch())
                    .transform(new Transformer(pluginFinder))
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(new RedefinitionListener())
                    .with(new Listener())
                    .installOn(instrumentation);

        // 省略部分代码
    }

    // 省略部分代码
}
```
Byte Buddy 有两个地方的代码我们需要重点关注：
- `agentBuilder.type(pluginFinder.buildMatch())` 用于告诉 Byte Buddy 对哪些类进行增强，就是我们插件定义类中的`AbstractClassEnhancePluginDefine#enhanceClass` 方法声明的目标类的匹配规则；
- `transform(new Transformer(pluginFinder))` 用于匹配到的目标类进行增强，调用了插件定义类中的 `AbstractClassEnhancePluginDefine#define` 方法。

我们看下 `PluginFinder#buildMatch()` 方法的实现
```java
/**
 * The <code>PluginFinder</code> represents a finder , which assist to find the one from the given {@link
 * AbstractClassEnhancePluginDefine} list.
 */
public class PluginFinder {
    private final Map<String, LinkedList<AbstractClassEnhancePluginDefine>> nameMatchDefine = new HashMap<String, LinkedList<AbstractClassEnhancePluginDefine>>();
    private final List<AbstractClassEnhancePluginDefine> signatureMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();
    private final List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();

    // 省略部分代码

    public ElementMatcher<? super TypeDescription> buildMatch() {
        ElementMatcher.Junction judge = new AbstractJunction<NamedElement>() {
            @Override
            public boolean matches(NamedElement target) {
                // 根据类名匹配
                return nameMatchDefine.containsKey(target.getActualName());
            }
        };
        judge = judge.and(not(isInterface()));
        for (AbstractClassEnhancePluginDefine define : signatureMatchDefine) {
            ClassMatch match = define.enhanceClass();
            if (match instanceof IndirectMatch) {
                // 间接匹配（比如 PrefixMatch、MethodAnnotationMatch、RegexMatch 等）
                judge = judge.or(((IndirectMatch) match).buildJunction());
            }
        }
        return new ProtectiveShieldMatcher(judge);
    }

    public List<AbstractClassEnhancePluginDefine> getBootstrapClassMatchDefine() {
        return bootstrapClassMatchDefine;
    }
}
```
其中 `target.getActualName()` 是 Byte Buddy 的 API，用于获取正在加载的类的全类名。


SkyWalking 通过自定义 Transformer 对目标类增强，`PluginFinder` 类里面包含我们之前实例化的插件定义类。
```java
private static class Transformer implements AgentBuilder.Transformer {
    private PluginFinder pluginFinder;

    Transformer(PluginFinder pluginFinder) {
        this.pluginFinder = pluginFinder;
    }

    @Override
    public DynamicType.Builder<?> transform(final DynamicType.Builder<?> builder,
                                            final TypeDescription typeDescription,
                                            final ClassLoader classLoader,
                                            final JavaModule module) {
        LoadedLibraryCollector.registerURLClassLoader(classLoader);
        // 根据目标类（比如 tomcat 中的 org.apache.catalina.core.StandardHostValve 类 ）找到对应的增强插件定义类
        List<AbstractClassEnhancePluginDefine> pluginDefines = pluginFinder.find(typeDescription);
        if (pluginDefines.size() > 0) {
            DynamicType.Builder<?> newBuilder = builder;
            EnhanceContext context = new EnhanceContext();
            for (AbstractClassEnhancePluginDefine define : pluginDefines) {
                // 调用增强插件的 define 方法
                DynamicType.Builder<?> possibleNewBuilder = define.define(
                        typeDescription, newBuilder, classLoader, context);
                if (possibleNewBuilder != null) {
                    newBuilder = possibleNewBuilder;
                }
            }
            if (context.isEnhanced()) {
                LOGGER.debug("Finish the prepare stage for {}.", typeDescription.getName());
            }

            return newBuilder;
        }

        LOGGER.debug("Matched class {}, but ignore by finding mechanism.", typeDescription.getTypeName());
        return builder;
    }
}
```
首先通过 `PluginFinder#find` 方法查找目标类对应的插件定义类，我们先看下是如何查找的
```java
/**
 * The <code>PluginFinder</code> represents a finder , which assist to find the one from the given {@link
 * AbstractClassEnhancePluginDefine} list.
 */
public class PluginFinder {
    private final Map<String, LinkedList<AbstractClassEnhancePluginDefine>> nameMatchDefine = new HashMap<String, LinkedList<AbstractClassEnhancePluginDefine>>();
    private final List<AbstractClassEnhancePluginDefine> signatureMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();
    private final List<AbstractClassEnhancePluginDefine> bootstrapClassMatchDefine = new ArrayList<AbstractClassEnhancePluginDefine>();

    // 省略部分代码

    public List<AbstractClassEnhancePluginDefine> find(TypeDescription typeDescription) {
        List<AbstractClassEnhancePluginDefine> matchedPlugins = new LinkedList<AbstractClassEnhancePluginDefine>();
        // 获取类的全类名
        String typeName = typeDescription.getTypeName();
        if (nameMatchDefine.containsKey(typeName)) {
            matchedPlugins.addAll(nameMatchDefine.get(typeName));
        }

        for (AbstractClassEnhancePluginDefine pluginDefine : signatureMatchDefine) {
            IndirectMatch match = (IndirectMatch) pluginDefine.enhanceClass();
            if (match.isMatch(typeDescription)) {
                matchedPlugins.add(pluginDefine);
            }
        }

        return matchedPlugins;
    }

    // 省略部分代码
    
}
```
查找过程就是从不同的插件分类中去匹配，其中 `TypeDescription.getTypeName()` 通过 Byte Buddy 的 API 获取类的全类名。

获取到目标类对应的插件定义类之后，就是调用插件定义类中的 `AbstractClassEnhancePluginDefine#define` 方法对类进行增强，这里就是前面文章[Apache SkyWalking Java Agent 06-插件定义体系](https://juejin.cn/post/7109032881938235423) 中最后留下的问题答案。

通过以上分析我们可以看到之前对插件定义类的所有的工作（加载、实例化等）都是为这里的 Byte Buddy 服务的，告诉 Byte Buddy 对哪些类进行增强。

那么`AbstractClassEnhancePluginDefine#define ` 是如何对目标类进行增强呢，我将在下一篇进行介绍，敬请关注。

我正在参与掘金技术社区创作者签约计划招募活动，[点击链接报名投稿](https://juejin.cn/post/7112770927082864653)
