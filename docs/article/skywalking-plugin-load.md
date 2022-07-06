Apache SkyWalking Java Agent 05-插件加载机制（下）

上一篇文章中我们重点分析了自定义类加载器 AgentClassLoader.initDefaultLoader() 部分，AgentClassLoader 初始化主要是定位 skywalking-agent.jar 所在目录以及成员变量 DEFAULT_LOADER 和 classpath 的初始化。

AgentClassLoader 主要负责查找插件和拦截器，其中插件位于 skywalking-agent.jar 目录下的 Config.Plugin.MOUNT 配置所在目录（默认目录是 plugins 和 activations）。

```java
/**
 * Plugins finder. Use {@link PluginResourcesResolver} to find all plugins, and ask {@link PluginCfg} to load all plugin
 * definitions.
 */
public class PluginBootstrap {
    private static final ILog LOGGER = LogManager.getLogger(PluginBootstrap.class);

    /**
     * load all plugins.
     *
     * @return plugin definition list.
     */
    public List<AbstractClassEnhancePluginDefine> loadPlugins() throws AgentPackageNotFoundException {
        // 1.初始化 AgentClassLoader
        AgentClassLoader.initDefaultLoader();

        PluginResourcesResolver resolver = new PluginResourcesResolver();
       // 2.使用 AgentClassLoader 读取插件定义文件 skywalking-plugin.def
        List<URL> resources = resolver.getResources();

        if (resources == null || resources.size() == 0) {
            LOGGER.info("no plugin files (skywalking-plugin.def) found, continue to start application.");
            return new ArrayList<AbstractClassEnhancePluginDefine>();
        }

        for (URL pluginUrl : resources) {
            try {
                // 3.读取插件定义文件 skywalking-plugin.def 内容，封装成 PluginDefine
                PluginCfg.INSTANCE.load(pluginUrl.openStream());
            } catch (Throwable t) {
                LOGGER.error(t, "plugin file [{}] init failure.", pluginUrl);
            }
        }

        List<PluginDefine> pluginClassList = PluginCfg.INSTANCE.getPluginClassList();

        List<AbstractClassEnhancePluginDefine> plugins = new ArrayList<AbstractClassEnhancePluginDefine>();
        for (PluginDefine pluginDefine : pluginClassList) {
            try {
                LOGGER.debug("loading plugin class {}.", pluginDefine.getDefineClass());
                // 4.使用 AgentClassLoader 加载并实例化插件定义类
                AbstractClassEnhancePluginDefine plugin = (AbstractClassEnhancePluginDefine) Class.forName(pluginDefine.getDefineClass(), true, AgentClassLoader
                    .getDefault()).newInstance();
                plugins.add(plugin);
            } catch (Throwable t) {
                LOGGER.error(t, "load plugin [{}] failure.", pluginDefine.getDefineClass());
            }
        }

        plugins.addAll(DynamicPluginLoader.INSTANCE.load(AgentClassLoader.getDefault()));

        return plugins;

    }

}
```

AgentClassLoader初始化完成之后，接下来由 PluginResourcesResolver 负责调用 AgentClassLoader 读取所有插件定义文件（skywalking-plugin.def）

```java
PluginResourcesResolver resolver = new PluginResourcesResolver();
// 2.使用 AgentClassLoader 读取插件定义文件 skywalking-plugin.def
List<URL> resources = resolver.getResources();
```

我们这里以tomcat插件为例看下插件定义文件的内容，tomcat插件位于 apm-sniffer/apm-sdk-plugin/tomcat-7.x-8.x-plugin/src/main/resources/skywalking-plugin.def 内容如下：

```text
tomcat-7.x/8.x=org.apache.skywalking.apm.plugin.tomcat78x.define.TomcatInstrumentation
tomcat-7.x/8.x=org.apache.skywalking.apm.plugin.tomcat78x.define.ApplicationDispatcherInstrumentation
```

插件定义格式 pluginName=defineClass 的形式。

PluginResourcesResolver.getResources 方法

```java
/**
 * Use the current classloader to read all plugin define file. The file must be named 'skywalking-plugin.def'
 */
public class PluginResourcesResolver {
    private static final ILog LOGGER = LogManager.getLogger(PluginResourcesResolver.class);

    public List<URL> getResources() {
        List<URL> cfgUrlPaths = new ArrayList<URL>();
        Enumeration<URL> urls;
        try {
            // getResources(name) 方法内部会调用 AgentClassLoader 重写的 findResources 方法
            urls = AgentClassLoader.getDefault().getResources("skywalking-plugin.def");

            while (urls.hasMoreElements()) {
                URL pluginUrl = urls.nextElement();
                cfgUrlPaths.add(pluginUrl);
              	// jar:file:/path/to/skywalking-java/skywalking-agent/plugins/tomcat-7.x-8.x-plugin-8.8.0.jar!/skywalking-plugin.def 
                LOGGER.info("find skywalking plugin define in {}", pluginUrl);
            }

            return cfgUrlPaths;
        } catch (IOException e) {
            LOGGER.error("read resources failure.", e);
        }
        return null;
    }
}
```

这里很简单，就是调用 AgentClassLoader 的 getResources 方法，getResources 方法是在父类 ClassLoader 中定义的，根据类加载器的委托机制（可以看下 ClassLoader.getResources 的具体实现，这里就不赘述了），最后的会调用 AgentClassLoader 实现的 findResources 方法，得到资源的URL。

继续回到 PluginBootstrap.loadPlugins 方法内部，开始遍历资源的URL

```java
for (URL pluginUrl : resources) {
   try {
     // 读取插件定义文件 skywalking-plugin.def 内容，封装成 PluginDefine
     PluginCfg.INSTANCE.load(pluginUrl.openStream());
   } catch (Throwable t) {
     LOGGER.error(t, "plugin file [{}] init failure.", pluginUrl);
   }
}
```

PluginCfg 是一个通过枚举实现的单例，主要负责读取插件定义文件（skywalking-plugin.def）的内容，按行读取封装成 PluginDefine，并放入成员变量 pluginClassList 中，PluginCfg 提供了 getPluginClassList 方法用于获取读取到的插件定义信息。

```java
public enum PluginCfg {
    INSTANCE;

    private static final ILog LOGGER = LogManager.getLogger(PluginCfg.class);

    private List<PluginDefine> pluginClassList = new ArrayList<PluginDefine>();
    private PluginSelector pluginSelector = new PluginSelector();

    void load(InputStream input) throws IOException {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String pluginDefine;
            while ((pluginDefine = reader.readLine()) != null) {
                try {
                    if (pluginDefine.trim().length() == 0 || pluginDefine.startsWith("#")) {
                        continue;
                    }
                    // 读取插件定义文件的每一行，比如 dubbo=org.apache.skywalking.apm.plugin.asf.dubbo.DubboInstrumentation
                    // 将每一行的 name=class 封装成 PluginDefine
                    PluginDefine plugin = PluginDefine.build(pluginDefine);
                    pluginClassList.add(plugin);
                } catch (IllegalPluginDefineException e) {
                    LOGGER.error(e, "Failed to format plugin({}) define.", pluginDefine);
                }
            }
            // 根据 Config.Plugin.EXCLUDE_PLUGINS 配置的插件名称排除部分插件
            pluginClassList = pluginSelector.select(pluginClassList);
        } finally {
            input.close();
        }
    }

    public List<PluginDefine> getPluginClassList() {
        return pluginClassList;
    }

}
```

接着从 PluginCfg 中获取插件定义信息，调用 Class.forName 获取插件定义类的 Class对象。

```java
/**
 * Plugins finder. Use {@link PluginResourcesResolver} to find all plugins, and ask {@link PluginCfg} to load all plugin
 * definitions.
 */
public class PluginBootstrap {
    private static final ILog LOGGER = LogManager.getLogger(PluginBootstrap.class);

    /**
     * load all plugins.
     *
     * @return plugin definition list.
     */
    public List<AbstractClassEnhancePluginDefine> loadPlugins() throws AgentPackageNotFoundException {
        
				// 省略其他部分代码
      
        List<PluginDefine> pluginClassList = PluginCfg.INSTANCE.getPluginClassList();

        List<AbstractClassEnhancePluginDefine> plugins = new ArrayList<AbstractClassEnhancePluginDefine>();
        for (PluginDefine pluginDefine : pluginClassList) {
            try {
                LOGGER.debug("loading plugin class {}.", pluginDefine.getDefineClass());
                // 使用 AgentClassLoader 加载并实例化插件定义类
                AbstractClassEnhancePluginDefine plugin = (AbstractClassEnhancePluginDefine) Class.forName(pluginDefine.getDefineClass(), true, AgentClassLoader
                    .getDefault()).newInstance();
                plugins.add(plugin);
            } catch (Throwable t) {
                LOGGER.error(t, "load plugin [{}] failure.", pluginDefine.getDefineClass());
            }
        }

      // 省略其他部分代码
    }
}

```

我们重点看下 Class.forName 部分，这里指定了类加载器为 AgentClassLoader.getDefault()

```java
public static Class<?> forName(String name, boolean initialize, ClassLoader loader)
        throws ClassNotFoundException
```

Class.forName(defineClass, true, AgentClassLoader.getDefault()) 使用 AgentClassLoader 类加载器时，JVM底层会调用 ClassLoader.loadClass 方法，根据类加载器的委托机制，ClassLoader 的 loadClass 方法的内部实现会调用子类 AgentClassLoader 的 findClass 方法，因为 AgentClassLoader 的所有父加载器都不知道怎么去加载这些插件定义类，只有 AgentClassLoader 知道如何去找。

AgentClassLoader.findClass 实现如下：

```java
/**
 * The <code>AgentClassLoader</code> represents a classloader, which is in charge of finding plugins and interceptors.
 */
public class AgentClassLoader extends ClassLoader {
		// 省略其他部分代码
    
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        List<Jar> allJars = getAllJars();
        String path = name.replace('.', '/').concat(".class");
        for (Jar jar : allJars) {
            JarEntry entry = jar.jarFile.getJarEntry(path);
            if (entry == null) {
                continue;
            }
            try {
                URL classFileUrl = new URL("jar:file:" + jar.sourceFile.getAbsolutePath() + "!/" + path);
                byte[] data;
                try (final BufferedInputStream is = new BufferedInputStream(
                    classFileUrl.openStream()); final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    int ch;
                    while ((ch = is.read()) != -1) {
                        baos.write(ch);
                    }
                    data = baos.toByteArray();
                }
                // defineClass 方法用于将类的字节数组转换成类的 Class 对象
                return processLoadedClass(defineClass(name, data, 0, data.length));
            } catch (IOException e) {
                LOGGER.error(e, "find class fail.");
            }
        }
        throw new ClassNotFoundException("Can't find " + name);
    }
  
   // 省略其他部分代码
}

```



获取到插件定义类的Class对象之后，然后调用 Class.newInstance 方法实例化这些插件定义类，再将插件定义类对象强转成 AbstractClassEnhancePluginDefine 类对象，这里可以看出所有的插件定义类都是 AbstractClassEnhancePluginDefine 的子类。

到这里完成了插件的加载过程，主要是插件的查找和实例化。其中 AgentClassLoader 在插件加载过程扮演着非常重要的角色，我们之前可能只是在一些书上看到过 Java 的自定义类加载器，可以说 SkyWalking Java Agent 对类加载器委托机制理论的实战。

那么插件定义类的作用是什么呢，我将在下一篇进行介绍，敬请关注。