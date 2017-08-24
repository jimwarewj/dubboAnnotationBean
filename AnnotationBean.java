package com.yao.eas.business.common.utils;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.config.*;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by wangjing0131 on 2017/8/21.
 */
public class AnnotationBean  extends AbstractConfig implements DisposableBean, BeanFactoryPostProcessor, BeanPostProcessor, ApplicationContextAware {
    private String annotationPackage;
    private String[] annotationPackages;
    private ApplicationContext applicationContext;
    private final ConcurrentMap<String, ReferenceBean<?>> referenceConfigs = new ConcurrentHashMap();
    private final Set<ServiceConfig<?>> serviceConfigs = new ConcurrentHashSet();
    private static final Logger logger = LoggerFactory.getLogger(Logger.class);
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
    public String getPackage() {
        return this.annotationPackage;
    }

    public void setPackage(String annotationPackage) {
        this.annotationPackage = annotationPackage;
        this.annotationPackages = annotationPackage != null && annotationPackage.length() != 0? Constants.COMMA_SPLIT_PATTERN.split(annotationPackage):null;
    }
    /**
     * 获取动态代理类的原始类全名称
     * @param bean 动态代理类的类对象
     * @return 原始类的类全名称
     */
    private String getProxyBeanClassName(Object bean) {
        // 此时bean为代理类，需要获取真正的类名
        Class<?> interfaceCls = bean.getClass().getInterfaces()[0];
        // 接口的实现类正好是在接口的impl包下，并且实现类名称为接口名称加上Impl结尾
        StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(interfaceCls.getPackage().getName()).append(".Impl.").append(interfaceCls.getSimpleName()).append("Impl");
        return nameBuilder.toString();
    }
    private Class getBeanClass(Object bean) {
        Class clazz = bean.getClass();
        if (AopUtils.isAopProxy(bean)) {
            clazz = AopUtils.getTargetClass(bean);
        }
        return clazz;
    }
    /**
     * 是否是由动态代理生成的类
     */
    private boolean isProxy(Object bean) {
        if (bean instanceof Proxy) {
            return true;
        }
        return false;
    }

    /**
     * @doc 判断该类bean是否在指定包下，并且是否属于代理类,只处理代理类
     * @param bean
     * @return
     */
    private boolean isMatchPackage(Object bean) {
        if(this.annotationPackages != null && this.annotationPackages.length != 0) {
            Class clazz = getBeanClass(bean);
            String beanClassName = clazz.getName();
            for (String pkg : annotationPackages) {
                if (beanClassName.startsWith(pkg)) {
                    if(AopUtils.isAopProxy(bean)){
                        return true;
                    }else
                        return false;
                }
            }
            return false;
        } else {
            return true;
        }
    }
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        if (annotationPackage == null || annotationPackage.length() == 0) {
            return;
        }
        if (beanFactory instanceof BeanDefinitionRegistry) {
            try {
                // init scanner
                Class<?> scannerClass = ReflectUtils.forName("org.springframework.context.annotation.ClassPathBeanDefinitionScanner");
                Object scanner = scannerClass.getConstructor(new Class<?>[] {BeanDefinitionRegistry.class, boolean.class}).newInstance(new Object[] {(BeanDefinitionRegistry) beanFactory, true});
                // add filter
                Class<?> filterClass = ReflectUtils.forName("org.springframework.core.type.filter.AnnotationTypeFilter");
                Object filter = filterClass.getConstructor(Class.class).newInstance(Service.class);
                Method addIncludeFilter = scannerClass.getMethod("addIncludeFilter", ReflectUtils.forName("org.springframework.core.type.filter.TypeFilter"));
                addIncludeFilter.invoke(scanner, filter);
                // scan packages
                String[] packages = Constants.COMMA_SPLIT_PATTERN.split(annotationPackage);
                Method scan = scannerClass.getMethod("scan", new Class<?>[]{String[].class});
                scan.invoke(scanner, new Object[] {packages});
            } catch (Throwable e) {
                // spring 2.0
            }
        }
    }
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if(!this.isMatchPackage(bean)) {
            return bean;
        } else {
            Service service = null;
            Class clazz = getBeanClass(bean);
            service = (Service) clazz.getAnnotation(Service.class);
            if(service != null) {
                ServiceBean<Object> serviceConfig = new ServiceBean<Object>(service);
                if (void.class.equals(service.interfaceClass()) && "".equals(service.interfaceName())) {
                    if (clazz.getInterfaces().length > 0) {
                        serviceConfig.setInterface(clazz.getInterfaces()[0]);
                    } else {
                        throw new IllegalStateException("Failed to export remote service class " + clazz.getName()
                                + ", cause: The @Service undefined interfaceClass or interfaceName, and the service class unimplemented any interfaces.");
                    }
                }
                if (applicationContext != null) {
                    serviceConfig.setApplicationContext(applicationContext);
                    if (service.registry() != null && service.registry().length > 0) {
                        List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                        for (String registryId : service.registry()) {
                            if (registryId != null && registryId.length() > 0) {
                                registryConfigs
                                        .add((RegistryConfig) applicationContext.getBean(registryId, RegistryConfig.class));
                            }
                        }
                        serviceConfig.setRegistries(registryConfigs);
                    }
                    if (service.provider() != null && service.provider().length() > 0) {
                        serviceConfig.setProvider(
                                (ProviderConfig) applicationContext.getBean(service.provider(), ProviderConfig.class));
                    }
                    if (service.monitor() != null && service.monitor().length() > 0) {
                        serviceConfig.setMonitor(
                                (MonitorConfig) applicationContext.getBean(service.monitor(), MonitorConfig.class));
                    }
                    if (service.application() != null && service.application().length() > 0) {
                        serviceConfig.setApplication((ApplicationConfig) applicationContext.getBean(service.application(),
                                ApplicationConfig.class));
                    }
                    if (service.module() != null && service.module().length() > 0) {
                        serviceConfig
                                .setModule((ModuleConfig) applicationContext.getBean(service.module(), ModuleConfig.class));
                    }
                    if (service.provider() != null && service.provider().length() > 0) {
                        serviceConfig.setProvider(
                                (ProviderConfig) applicationContext.getBean(service.provider(), ProviderConfig.class));
                    } else {

                    }
                    if (service.protocol() != null && service.protocol().length > 0) {
                        List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                        for (String protocolId : service.registry()) {
                            if (protocolId != null && protocolId.length() > 0) {
                                protocolConfigs
                                        .add((ProtocolConfig) applicationContext.getBean(protocolId, ProtocolConfig.class));
                            }
                        }
                        serviceConfig.setProtocols(protocolConfigs);
                    }
                    try {
                        serviceConfig.afterPropertiesSet();
                    } catch (RuntimeException e) {
                        throw (RuntimeException) e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                }
                serviceConfig.setRef(bean);
                serviceConfigs.add(serviceConfig);
                serviceConfig.export();
            }

            return bean;
        }
    }
    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        if (! isMatchPackage(bean)) {
            return bean;
        }
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            String name = method.getName();
            if (name.length() > 3 && name.startsWith("set")
                    && method.getParameterTypes().length == 1
                    && Modifier.isPublic(method.getModifiers())
                    && ! Modifier.isStatic(method.getModifiers())) {
                try {
                    Reference reference = method.getAnnotation(Reference.class);
                    if (reference != null) {
                        Object value = refer(reference, method.getParameterTypes()[0]);
                        if (value != null) {
                            method.invoke(bean, new Object[] {  });
                        }
                    }
                } catch (Throwable e) {
                    logger.error("Failed to init remote service reference at method " + name + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
                }
            }
        }
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            try {
                if (! field.isAccessible()) {
                    field.setAccessible(true);
                }
                Reference reference = field.getAnnotation(Reference.class);
                if (reference != null) {
                    Object value = refer(reference, field.getType());
                    if (value != null) {
                        field.set(bean, value);
                    }
                }
            } catch (Throwable e) {
                logger.error("Failed to init remote service reference at filed " + field.getName() + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
            }
        }
        return bean;
    }
    private Object refer(Reference reference, Class<?> referenceClass) {
        String interfaceName;
        if(!"".equals(reference.interfaceName())) {
            interfaceName = reference.interfaceName();
        } else if(!Void.TYPE.equals(reference.interfaceClass())) {
            interfaceName = reference.interfaceClass().getName();
        } else {
            if(!referenceClass.isInterface()) {
                throw new IllegalStateException("The @Reference undefined interfaceClass or interfaceName, and the property type " + referenceClass.getName() + " is not a interface.");
            }

            interfaceName = referenceClass.getName();
        }

        String key = reference.group() + "/" + interfaceName + ":" + reference.version();
        ReferenceBean<?> referenceConfig = (ReferenceBean)this.referenceConfigs.get(key);
        if(referenceConfig == null) {
            referenceConfig = new ReferenceBean(reference);
            if(Void.TYPE.equals(reference.interfaceClass()) && "".equals(reference.interfaceName()) && referenceClass.isInterface()) {
                referenceConfig.setInterface(referenceClass);
            }

            if(this.applicationContext != null) {
                referenceConfig.setApplicationContext(this.applicationContext);
                if(reference.registry() != null && reference.registry().length > 0) {
                    List<RegistryConfig> registryConfigs = new ArrayList();
                    String[] arr$ = reference.registry();
                    int len$ = arr$.length;

                    for(int i$ = 0; i$ < len$; ++i$) {
                        String registryId = arr$[i$];
                        if(registryId != null && registryId.length() > 0) {
                            registryConfigs.add((RegistryConfig)this.applicationContext.getBean(registryId, RegistryConfig.class));
                        }
                    }

                    referenceConfig.setRegistries(registryConfigs);
                }

                if(reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig)this.applicationContext.getBean(reference.consumer(), ConsumerConfig.class));
                }

                if(reference.monitor() != null && reference.monitor().length() > 0) {
                    referenceConfig.setMonitor((MonitorConfig)this.applicationContext.getBean(reference.monitor(), MonitorConfig.class));
                }

                if(reference.application() != null && reference.application().length() > 0) {
                    referenceConfig.setApplication((ApplicationConfig)this.applicationContext.getBean(reference.application(), ApplicationConfig.class));
                }

                if(reference.module() != null && reference.module().length() > 0) {
                    referenceConfig.setModule((ModuleConfig)this.applicationContext.getBean(reference.module(), ModuleConfig.class));
                }

                if(reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig)this.applicationContext.getBean(reference.consumer(), ConsumerConfig.class));
                }

                try {
                    referenceConfig.afterPropertiesSet();
                } catch (RuntimeException var11) {
                    throw var11;
                } catch (Exception var12) {
                    throw new IllegalStateException(var12.getMessage(), var12);
                }
            }

            this.referenceConfigs.putIfAbsent(key, referenceConfig);
            referenceConfig = (ReferenceBean)this.referenceConfigs.get(key);
        }

        return referenceConfig.get();
    }
    @Override
    public void destroy() throws Exception {
        for (ServiceConfig serviceConfig : serviceConfigs) {
            try {
                serviceConfig.unexport();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
        for (ReferenceConfig referenceConfig : referenceConfigs.values()) {
            try {
                referenceConfig.destroy();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}
