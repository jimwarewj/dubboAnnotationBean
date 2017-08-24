package com.yao.eas.business.common.utils;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Created by wangjing0131 on 2017/8/21.
 */
@Configuration
public class DubboConfiguration implements ApplicationContextAware {
    private ApplicationContext applicationContext;
    @Bean
    public AnnotationBean dubboAnnotationBean() {
        AnnotationBean annotationBean = new AnnotationBean();
        try {
            com.alibaba.dubbo.config.spring.AnnotationBean bean = this.applicationContext.getBean(com.alibaba.dubbo.config.spring.AnnotationBean.class);
            annotationBean.setPackage(bean.getPackage());
        }catch (Exception e){
        }
        return annotationBean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext=applicationContext;
    }
}
