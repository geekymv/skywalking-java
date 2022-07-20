Apache SkyWalking Java Agent 08-增强实例方法

通过上一篇文章[Apache SkyWalking Java Agent 06-插件定义体系]()我们了解到插件定义顶层类 `AbstractClassEnhancePluginDefine` 是一个抽象类，它提供了如何对目标类增强的模版方法，define 方法是增强目标类的主要入口，define 方法内部调用了两个抽象方法 `enhanceInstance` 和 `enhanceClass` 方法，分别用于增强实例方法（包括构造方法）、静态方法，由具体子类实现。

### AbstractClassEnhancePluginDefine 插件定义的抽象

`AbstractClassEnhancePluginDefine` 抽象类有两个直接抽象子类 `ClassEnhancePluginDefine`和 `ClassEnhancePluginDefineV2`，我们后面重点分析`ClassEnhancePluginDefine` 及其子类，看懂了`ClassEnhancePluginDefine ` 再去看 `ClassEnhancePluginDefineV2` 会很容易。

### ClassEnhancePluginDefine 插件定义类

`AbstractClassEnhancePluginDefine` 类定义了模版方法，子类 `ClassEnhancePluginDefine` 则控制了所有的增强操作，包括：

- 增强构造方法、实例方法和静态方法；

- 所有的增强基于三个拦截点 `ConstructorInterceptPoint`、`InstanceMethodsInterceptPoint`、`StaticMethodsInterceptPoint`；

- 如果插件增强构造方法、实例方法，或者其中一个，`ClassEnhancePluginDefine` 将会给目标类增加一个 Onject 类型的属性。

具体描述可以看`ClassEnhancePluginDefine` 类的 Javadoc 说明。

`ClassEnhancePluginDefine` 主要对父类中定义的两个抽象方法 `enhanceInstance` 和 `enhanceClass` 方法的具体实现，这里我们以 `enhanceInstance` 为例进行介绍。

```java
/**
 * This class controls all enhance operations, including enhance constructors, instance methods and static methods. All
 * the enhances base on three types interceptor point: {@link ConstructorInterceptPoint}, {@link
 * InstanceMethodsInterceptPoint} and {@link StaticMethodsInterceptPoint} If plugin is going to enhance constructors,
 * instance methods, or both, {@link ClassEnhancePluginDefine} will add a field of {@link Object} type.
 */
public abstract class ClassEnhancePluginDefine extends AbstractClassEnhancePluginDefine {
    private static final ILog LOGGER = LogManager.getLogger(ClassEnhancePluginDefine.class);

    /**
     * Enhance a class to intercept constructors and class instance methods.
     * 增强类的构造方法和实例方法
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    protected DynamicType.Builder<?> enhanceInstance(TypeDescription typeDescription,
        DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader,
        EnhanceContext context) throws PluginException {
        // 获取构造方法拦截点
        ConstructorInterceptPoint[] constructorInterceptPoints = getConstructorsInterceptPoints();
        // 获取实例方法拦截点
        InstanceMethodsInterceptPoint[] instanceMethodsInterceptPoints = getInstanceMethodsInterceptPoints();
        String enhanceOriginClassName = typeDescription.getTypeName();
        boolean existedConstructorInterceptPoint = false;
        if (constructorInterceptPoints != null && constructorInterceptPoints.length > 0) {
            existedConstructorInterceptPoint = true;
        }
        boolean existedMethodsInterceptPoints = false;
        if (instanceMethodsInterceptPoints != null && instanceMethodsInterceptPoints.length > 0) {
            existedMethodsInterceptPoints = true;
        }

        /**
         * nothing need to be enhanced in class instance, maybe need enhance static methods.
         */
        if (!existedConstructorInterceptPoint && !existedMethodsInterceptPoints) {
            return newClassBuilder;
        }

        /**
         * Manipulate class source code.<br/>
         *
         * new class need:<br/>
         * 1.Add field, name {@link #CONTEXT_ATTR_NAME}.
         * 2.Add a field accessor for this field.
         *
         * And make sure the source codes manipulation only occurs once.
         *
         * 操作类的字节码
         * 1.增加一个属性 _$EnhancedClassField_ws
         * 2.给属性增加一个访问器
         */
        LOGGER.info("EnhancedInstance class loader is " + EnhancedInstance.class.getClassLoader());
        if (!typeDescription.isAssignableTo(EnhancedInstance.class)) {
            // 待增强类（比如 com.alibaba.dubbo.monitor.support.MonitorFilter ）还没有实现 EnhancedInstance 接口
            if (!context.isObjectExtended()) {
                // 给类增加一个属性并给属性增加访问器，让类实现 EnhancedInstance 接口，通过访问接口中的 setter getter 方法去访问这个属性
                newClassBuilder = newClassBuilder.defineField(
                    CONTEXT_ATTR_NAME, Object.class, ACC_PRIVATE | ACC_VOLATILE)
                                                 .implement(EnhancedInstance.class)
                                                 .intercept(FieldAccessor.ofField(CONTEXT_ATTR_NAME));
                context.extendObjectCompleted();
            }
        }

        /**
         * 2. enhance constructors
         * 增强构造方法
         */
        if (existedConstructorInterceptPoint) {
            for (ConstructorInterceptPoint constructorInterceptPoint : constructorInterceptPoints) {
                if (isBootstrapInstrumentation()) {
                    // 判断是否为启动类加载器加载的类的增强插件，参见 org.apache.skywalking.apm.plugin.jdk.threading.define.RunnableInstrumentation
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher())
                                                     .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.withDefaultConfiguration()
                                                                                                                 .to(BootstrapInstrumentBoost
                                                                                                                     .forInternalDelegateClass(constructorInterceptPoint
                                                                                                                         .getConstructorInterceptor()))));
                } else {
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher())
                                                     .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.withDefaultConfiguration()
                                                                                                                 .to(new ConstructorInter(constructorInterceptPoint
                                                                                                                     .getConstructorInterceptor(), classLoader))));
                }
            }
        }

        /**
         * 3. enhance instance methods
         * 增强实例方法
         */
        if (existedMethodsInterceptPoints) {
            for (InstanceMethodsInterceptPoint instanceMethodsInterceptPoint : instanceMethodsInterceptPoints) {
                String interceptor = instanceMethodsInterceptPoint.getMethodsInterceptor();
                if (StringUtil.isEmpty(interceptor)) {
                    throw new EnhanceException("no InstanceMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
                }
                ElementMatcher.Junction<MethodDescription> junction = not(isStatic()).and(instanceMethodsInterceptPoint.getMethodsMatcher());
                if (instanceMethodsInterceptPoint instanceof DeclaredInstanceMethodsInterceptPoint) {
                    junction = junction.and(ElementMatchers.<MethodDescription>isDeclaredBy(typeDescription));
                }
                if (instanceMethodsInterceptPoint.isOverrideArgs()) {
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                    .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                    } else {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                    .to(new InstMethodsInterWithOverrideArgs(interceptor, classLoader)));
                    }
                } else {
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                    } else {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .to(new InstMethodsInter(interceptor, classLoader)));
                    }
                }
            }
        }

        return newClassBuilder;
    }

    // 省略其他代码

}

```
下面我们开始读 `enhanceInstance` 方法的具体实现，首先看下 `enhanceInstance` 方法有哪些入参以及每个参数的作用，
- `TypeDescription typeDescription` Byte Buddy 的API，代表我们要增强的目标类（比如 Tomcat 中的 `org.apache.catalina.core.StandardHostValve` 类）的描述；
- `DynamicType.Builder<?> newClassBuilder` Byte Buddy 的API，用于操作目标类的字节码；
- `ClassLoader classLoader` 加载目标类的类加载器；
- `EnhanceContext context` 增强上下文，代表处理目标类的状态。

接下来我们进入 `enhanceInstance` 方法内部看下具体实现
```java
// 获取构造方法拦截点
ConstructorInterceptPoint[] constructorInterceptPoints = getConstructorsInterceptPoints();
// 获取实例方法拦截点
InstanceMethodsInterceptPoint[] instanceMethodsInterceptPoints = getInstanceMethodsInterceptPoints();
```
这一步是获取构造方法和实例方法的拦截点，`getConstructorsInterceptPoints` 和 `getInstanceMethodsInterceptPoints` 方法由具体的插件定义类实现，具体可以看下 Tomcat 的插件定义类 `TomcatInstrumentation` 的实现。

```java
boolean existedConstructorInterceptPoint = false;
if (constructorInterceptPoints != null && constructorInterceptPoints.length > 0) {
    existedConstructorInterceptPoint = true;
}
boolean existedMethodsInterceptPoints = false;
if (instanceMethodsInterceptPoints != null && instanceMethodsInterceptPoints.length > 0) {
    existedMethodsInterceptPoints = true;
}

/**
 * nothing need to be enhanced in class instance, maybe need enhance static methods.
 */
if (!existedConstructorInterceptPoint && !existedMethodsInterceptPoints) {
    return newClassBuilder;
}
```
构造方法拦截点和实例方法拦截点都没有定义的话，直接返回。


```java
/**
 * Manipulate class source code.<br/>
 *
 * new class need:<br/>
 * 1.Add field, name {@link #CONTEXT_ATTR_NAME}.
 * 2.Add a field accessor for this field.
 *
 * And make sure the source codes manipulation only occurs once.
 *
 */
if (!typeDescription.isAssignableTo(EnhancedInstance.class)) {
    if (!context.isObjectExtended()) {
        newClassBuilder = newClassBuilder.defineField(
            CONTEXT_ATTR_NAME, Object.class, ACC_PRIVATE | ACC_VOLATILE)
                                         .implement(EnhancedInstance.class)
                                         .intercept(FieldAccessor.ofField(CONTEXT_ATTR_NAME));
        context.extendObjectCompleted();
    }
}
```
操作目标类的字节码：
- 给目标类添加一个属性，名称为`_$EnhancedClassField_ws`，属性的访问修饰符为 `private` `volatile`；
- 给新增加的属性添加属性访问器，让类实现 EnhancedInstance 接口，通过访问接口中的 `setter` `getter` 方法去访问这个属性；
- `context.isObjectExtended()` 和 `context.extendObjectCompleted()` 方法用于保证添加属性的源码操作只发生一次，主要是修改增强上下文`EnhanceContext`中的`objectExtended`属性值。

接下来就是分别增强构造方法和实例方法，这里我们先看增强实例方法部分的实现代码
```java
/**
 * 3. enhance instance methods
 * 增强实例方法
 */
if (existedMethodsInterceptPoints) {
    for (InstanceMethodsInterceptPoint instanceMethodsInterceptPoint : instanceMethodsInterceptPoints) {
        // 遍历实例方法拦截点，获取实例方法拦截器的名称
        String interceptor = instanceMethodsInterceptPoint.getMethodsInterceptor();
        if (StringUtil.isEmpty(interceptor)) {
            throw new EnhanceException("no InstanceMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
        }
        ElementMatcher.Junction<MethodDescription> junction = not(isStatic()).and(instanceMethodsInterceptPoint.getMethodsMatcher());
        if (instanceMethodsInterceptPoint instanceof DeclaredInstanceMethodsInterceptPoint) {
            junction = junction.and(ElementMatchers.<MethodDescription>isDeclaredBy(typeDescription));
        }
        if (instanceMethodsInterceptPoint.isOverrideArgs()) {
            if (isBootstrapInstrumentation()) {
                newClassBuilder = newClassBuilder.method(junction)
                                                 .intercept(MethodDelegation.withDefaultConfiguration()
                                                                            .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                            .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
            } else {
                newClassBuilder = newClassBuilder.method(junction)
                                                 .intercept(MethodDelegation.withDefaultConfiguration()
                                                                            .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                            .to(new InstMethodsInterWithOverrideArgs(interceptor, classLoader)));
            }
        } else {
            if (isBootstrapInstrumentation()) {
                newClassBuilder = newClassBuilder.method(junction)
                                                 .intercept(MethodDelegation.withDefaultConfiguration()
                                                                            .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
            } else {
                newClassBuilder = newClassBuilder.method(junction)
                                                 .intercept(MethodDelegation.withDefaultConfiguration()
                                                                            .to(new InstMethodsInter(interceptor, classLoader)));
            }
        }
    }
}
```
遍历实例方法拦截点，获取实例方法拦截器的名称，验证名称不能为空，这个是我们在插件定义类中指定的拦截器类的全类名。

比如 Tomcat 的插件定义类 `TomcatInstrumentation` 中的构造方法拦截点中的声明，定义了两个拦截点，分别拦截 `invoke` 和 `throwable` 方法，每个要拦截的实例方法都有对应的方法拦截器（实例方法拦截器需要实现`InstanceMethodsAroundInterceptor` 接口，后面会介绍），比如 `invoke` 方法对应的方法拦截器为 `org.apache.skywalking.apm.plugin.tomcat78x.TomcatInvokeInterceptor`，它和插件定义类一样存在于插件的jar中。

```java
@Override
public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
    return new InstanceMethodsInterceptPoint[] {
        new InstanceMethodsInterceptPoint() {
            @Override
            public ElementMatcher<MethodDescription> getMethodsMatcher() {
                // 拦截的方法
                return named("invoke");
            }

            @Override
            public String getMethodsInterceptor() {
                // 对应的拦截器
                return INVOKE_INTERCEPT_CLASS;
            }

            @Override
            public boolean isOverrideArgs() {
                // 是否覆盖参数
                return false;
            }
        },
        new InstanceMethodsInterceptPoint() {
            @Override
            public ElementMatcher<MethodDescription> getMethodsMatcher() {
                return named("throwable");
            }

            @Override
            public String getMethodsInterceptor() {
                return EXCEPTION_INTERCEPT_CLASS;
            }

            @Override
            public boolean isOverrideArgs() {
                return false;
            }
        }
    };
}
```
`instanceMethodsInterceptPoint.isOverrideArgs()` 判断是否参数覆盖，当需要在拦截器里面对参数进行修改时，需要在插件定义类的拦截点中重写 `isOverrideArgs` 方法返回true；
`isBootstrapInstrumentation()` 方法用于判断待增强的目标类是否为JDK提供的类（这些类是由启动类加载器加载）；

### InstMethodsInter Byte Buddy 和 SkyWalking 插件之间的桥梁
那么我们先看个最简单的逻辑，也就是不覆盖参数，也不是JDK提供的类，最后一个 else 里面的代码
```java
newClassBuilder = newClassBuilder.method(junction)
                                 .intercept(MethodDelegation.withDefaultConfiguration()
                                 .to(new InstMethodsInter(interceptor, classLoader)));
```
在这里我们将看到一个最重要的类 `InstMethodsInter`，它是 Byte Buddy 拦截实例方法的拦截器，同时它是Byte Buddy 和 SkyWalking 插件的桥梁。
在介绍 `InstMethodsInter` 之前我们先看看我们在插件中定义的拦截器，比如上面提到的 Tomcat 插件中 `invoke` 方法对应的方法拦截器 `org.apache.skywalking.apm.plugin.tomcat78x.TomcatInvokeInterceptor`，
它实现了 `InstanceMethodsAroundInterceptor` 接口，声明如下：
```java
/**
 * A interceptor, which intercept method's invocation. The target methods will be defined in {@link
 * ClassEnhancePluginDefine}'s subclass, most likely in {@link ClassInstanceMethodsEnhancePluginDefine}
 */
public interface InstanceMethodsAroundInterceptor {
    /**
     * called before target method invocation.
     *
     * @param result change this result, if you want to truncate the method.
     */
    void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
        MethodInterceptResult result) throws Throwable;

    /**
     * called after target method invocation. Even method's invocation triggers an exception.
     *
     * @param ret the method's original return value. May be null if the method triggers an exception.
     * @return the method's actual return value.
     */
    Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
        Object ret) throws Throwable;

    /**
     * called when occur exception.
     *
     * @param t the exception occur.
     */
    void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
        Class<?>[] argumentsTypes, Throwable t);
}
```
看到 `InstanceMethodsAroundInterceptor` 接口中的方法声明是不是有种熟悉的感觉，跟 `AOP` 很像是吧。
- beforeMethod 在目标方法之前调用；
- afterMethod 在目标方法之后调用；
- handleMethodException 在目标方法发生异常的时候调用。

那么问题来了，Byte Buddy 是如何调用我们在插件中定义的拦截器的呢？这个就要交给我们上面提到的 `InstMethodsInter` 类来处理了，所以说它是Byte Buddy 和 SkyWalking 插件的桥梁。

```java
/**
 * The actual byte-buddy's interceptor to intercept class instance methods. In this class, it provide a bridge between
 * byte-buddy and sky-walking plugin.
 */
public class InstMethodsInter {
    private static final ILog LOGGER = LogManager.getLogger(InstMethodsInter.class);

    /**
     * An {@link InstanceMethodsAroundInterceptor} This name should only stay in {@link String}, the real {@link Class}
     * type will trigger classloader failure. If you want to know more, please check on books about Classloader or
     * Classloader appointment mechanism.
     */
    private InstanceMethodsAroundInterceptor interceptor;

    /**
     * @param instanceMethodsAroundInterceptorClassName class full name.
     */
    public InstMethodsInter(String instanceMethodsAroundInterceptorClassName, ClassLoader classLoader) {
        try {
            // 使用 AgentClassLoader 加载拦截器并实例化
            interceptor = InterceptorInstanceLoader.load(instanceMethodsAroundInterceptorClassName, classLoader);
        } catch (Throwable t) {
            throw new PluginException("Can't create InstanceMethodsAroundInterceptor.", t);
        }
    }

    /**
     * Intercept the target instance method.
     *
     * @param obj          target class instance.
     * @param allArguments all method arguments
     * @param method       method description.
     * @param zuper        the origin call ref.
     * @return the return value of target instance method.
     * @throws Exception only throw exception because of zuper.call() or unexpected exception in sky-walking ( This is a
     *                   bug, if anything triggers this condition ).
     */
    @RuntimeType
    public Object intercept(@This Object obj, @AllArguments Object[] allArguments, @SuperCall Callable<?> zuper,
        @Origin Method method) throws Throwable {
        EnhancedInstance targetObject = (EnhancedInstance) obj;

        MethodInterceptResult result = new MethodInterceptResult();
        try {
            // 目标方法执行前
            interceptor.beforeMethod(targetObject, method, allArguments, method.getParameterTypes(), result);
        } catch (Throwable t) {
            LOGGER.error(t, "class[{}] before method[{}] intercept failure", obj.getClass(), method.getName());
        }

        Object ret = null;
        try {
            if (!result.isContinue()) {
                ret = result._ret();
            } else {
                // 执行目标方法
                ret = zuper.call();
            }
        } catch (Throwable t) {
            try {
                // 目标方法发生异常
                interceptor.handleMethodException(targetObject, method, allArguments, method.getParameterTypes(), t);
            } catch (Throwable t2) {
                LOGGER.error(t2, "class[{}] handle method[{}] exception failure", obj.getClass(), method.getName());
            }
            throw t;
        } finally {
            try {
                // 目标方法执行后
                ret = interceptor.afterMethod(targetObject, method, allArguments, method.getParameterTypes(), ret);
            } catch (Throwable t) {
                LOGGER.error(t, "class[{}] after method[{}] intercept failure", obj.getClass(), method.getName());
            }
        }
        return ret;
    }
}
```
`new InstMethodsInter(interceptor, classLoader)` `InstMethodsInter` 的构造方法接收了两个参数
- interceptor 插件定义类中拦截点声明的拦截器全类名；
- classLoader 加载待增强目标类的类加载器。

`InstMethodsInter` 的构造方法接收到的是插件拦截器的全类名，那么我们如何获取到插件拦截器实例呢？这个时候我们想到了类加载器，由于插件拦截器是定义在插件里面的，可以像插件定义类一样通过 AgentClassLoader
来加载，插件定义类是这么被加载并实例化的
```java
AbstractClassEnhancePluginDefine plugin = (AbstractClassEnhancePluginDefine) Class.forName(pluginDefine.getDefineClass(), true, AgentClassLoader.getDefault()).newInstance();
```
那我们改造下
```java
InstanceMethodsAroundInterceptor interceptor = (InstanceMethodsAroundInterceptor) Class.forName(instanceMethodsAroundInterceptorClassName, true, AgentClassLoader.getDefault()).newInstance();
```
看起来好像可以，但是根据类加载器的隔离机制，我们自定义的类加载器 `AgentClassLoader` 加载的类，获取不到待增强目标类的相关类，因为目标类和拦截器类是由不同的类加载器加载的，目标类是由 `InstMethodsInter` 的构造方法接收到的 classLoader 加载的。
根据类加载器的委托机制，子加载器可以看到父加载器加载的类，父加载器看不到子加载器加载的类，插件中的拦截器需要使用目标类中的相关代码，比如 Tomcat 插件中的拦截器 `org.apache.skywalking.apm.plugin.tomcat78x.TomcatInvokeInterceptor` 需要获取请求URL、请求Method等，
所以我们可以将目标类的类加载器设置为 `AgentClassLoader` 的父加载器，这样插件中的拦截器就可以使用目标类中的相关代码了。SkyWalking 也确实是这么实现的，`InstMethodsInter` 的构造方法通过 `InterceptorInstanceLoader` 加载插件中定义的拦截器实例，后面会分析相关实现。
```java
/**
 * @param instanceMethodsAroundInterceptorClassName class full name.
 */
public InstMethodsInter(String instanceMethodsAroundInterceptorClassName, ClassLoader classLoader) {
    try {
        // 使用 AgentClassLoader 加载拦截器并实例化
        interceptor = InterceptorInstanceLoader.load(instanceMethodsAroundInterceptorClassName, classLoader);
    } catch (Throwable t) {
        throw new PluginException("Can't create InstanceMethodsAroundInterceptor.", t);
    }
}
```
拦截器实例获取到了，下面就该执行拦截器中的三个方法（`beforeMethod`、`afterMethod`、`handleMethodException`）了，`InstMethodsInter#intercept` 方法就是 Byte Buddy 中的拦截器方法，当调用我们的目标方法时，
会执行 `Java Agent` 中的拦截器方法，我们看下 `intercept` 具体实现，其实很简单，就是使用`try catch finally`在调用目标方法前、方法后、异常三种情况下分别调用插件中拦截器的三个方法，下面是具体代码实现：
```java
/**
 * Intercept the target instance method.
 *
 * @param obj          target class instance.
 * @param allArguments all method arguments
 * @param method       method description.
 * @param zuper        the origin call ref.
 * @return the return value of target instance method.
 * @throws Exception only throw exception because of zuper.call() or unexpected exception in sky-walking ( This is a
 *                   bug, if anything triggers this condition ).
 */
@RuntimeType
public Object intercept(@This Object obj, @AllArguments Object[] allArguments, @SuperCall Callable<?> zuper,
    @Origin Method method) throws Throwable {
    EnhancedInstance targetObject = (EnhancedInstance) obj;

    MethodInterceptResult result = new MethodInterceptResult();
    try {
        // 目标方法执行前
        interceptor.beforeMethod(targetObject, method, allArguments, method.getParameterTypes(), result);
    } catch (Throwable t) {
        LOGGER.error(t, "class[{}] before method[{}] intercept failure", obj.getClass(), method.getName());
    }

    Object ret = null;
    try {
        if (!result.isContinue()) {
            ret = result._ret();
        } else {
            // 执行目标方法
            ret = zuper.call();
        }
    } catch (Throwable t) {
        try {
            // 目标方法发生异常
            interceptor.handleMethodException(targetObject, method, allArguments, method.getParameterTypes(), t);
        } catch (Throwable t2) {
            LOGGER.error(t2, "class[{}] handle method[{}] exception failure", obj.getClass(), method.getName());
        }
        throw t;
    } finally {
        try {
            // 目标方法执行后
            ret = interceptor.afterMethod(targetObject, method, allArguments, method.getParameterTypes(), ret);
        } catch (Throwable t) {
            LOGGER.error(t, "class[{}] after method[{}] intercept failure", obj.getClass(), method.getName());
        }
    }
    return ret;
}
```

### InterceptorInstanceLoader 加载插件拦截器
下面我们重点看下 `InterceptorInstanceLoader` 加载插件拦截器相关代码
`InterceptorInstanceLoader` 是一个类查找器和容器，理解下面代码需要有类加载器委托机制的相关知识。
```java
/**
 * The <code>InterceptorInstanceLoader</code> is a classes finder and container.
 * <p>
 * This is a very important class in sky-walking's auto-instrumentation mechanism. If you want to fully understand why
 * need this, and how it works, you need have knowledge about Classloader appointment mechanism.
 * <p>
 */
public class InterceptorInstanceLoader {

    private static ConcurrentHashMap<String, Object> INSTANCE_CACHE = new ConcurrentHashMap<String, Object>();
    private static ReentrantLock INSTANCE_LOAD_LOCK = new ReentrantLock();
    private static Map<ClassLoader, ClassLoader> EXTEND_PLUGIN_CLASSLOADERS = new HashMap<ClassLoader, ClassLoader>();

    /**
     * Load an instance of interceptor, and keep it singleton. Create {@link AgentClassLoader} for each
     * targetClassLoader, as an extend classloader. It can load interceptor classes from plugins, activations folders.
     *
     * @param className         the interceptor class, which is expected to be found
     * @param targetClassLoader the class loader for current application context
     * @param <T>               expected type
     * @return the type reference.
     */
    public static <T> T load(String className,
        ClassLoader targetClassLoader) throws IllegalAccessException, InstantiationException, ClassNotFoundException, AgentPackageNotFoundException {
        if (targetClassLoader == null) {
            targetClassLoader = InterceptorInstanceLoader.class.getClassLoader();
        }
        // 插件拦截器类实例的缓存key
        String instanceKey = className + "_OF_" + targetClassLoader.getClass()
                                                                   .getName() + "@" + Integer.toHexString(targetClassLoader
            .hashCode());
        // 从缓存中获取拦截器类实例
        Object inst = INSTANCE_CACHE.get(instanceKey);
        if (inst == null) {
            INSTANCE_LOAD_LOCK.lock();
            ClassLoader pluginLoader;
            try {
                // 根据目标类加载器获取插件拦截器类加载器（一类目标类加载器实例对应一个插件拦截器类加载器实例）
                pluginLoader = EXTEND_PLUGIN_CLASSLOADERS.get(targetClassLoader);
                if (pluginLoader == null) {
                    // 创建一个指定 parent ClassLoader 的 AgentClassLoader 实例，用于加载插件类，这样插件代码才能使用目标类的相关类（类加载器的委托机制）
                    // targetClassLoader 是待增强类（目标类）的类加载器
                    pluginLoader = new AgentClassLoader(targetClassLoader);
                    EXTEND_PLUGIN_CLASSLOADERS.put(targetClassLoader, pluginLoader);
                }
            } finally {
                INSTANCE_LOAD_LOCK.unlock();
            }
            // 使用 AgentClassLoader 加载插件拦截器，并实例化
            inst = Class.forName(className, true, pluginLoader).newInstance();
            if (inst != null) {
                INSTANCE_CACHE.put(instanceKey, inst);
            }
        }

        return (T) inst;
    }
}
```
最后明确一点 `skywalking-agent.jar` 中的所有类和我们的应用程序中的类（待增强的目标类）一起是由 AppClassLoader 类加载器加载的，而我们定义的一些插件是由自定义的类加载器 `AgentClassLoader` 加载的，应用程序中的类（目标类）和插件拦截器类他们之间是互相不可见的，
需要将目标类的类加载器作为插件拦截器类的加载器 `AgentClassLoader` 的父加载器，这样插件拦截器类可以读取到目标类的相关类了。







