package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class RabbitmqConfig implements InitializingBean {
    @Resource
    private RabbitTemplate rabbitTemplate;

    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("初始化配置rabbitmq配置");

//        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(new ObjectMapper()));
        rabbitTemplate.setConfirmCallback(new RabbitTemplate.ConfirmCallback() {
            @Override
            public void confirm(CorrelationData correlationData, boolean b, String s) {
                if(!b)
                {
                    log.error("发送消息到mq失败,原因:{}",s);
                }
            }
        });
        rabbitTemplate.setReturnCallback(new RabbitTemplate.ReturnCallback() {
            @Override
            public void returnedMessage(Message message, int i, String s, String s1, String s2) {
                log.error("消息返回回调触发,交换机:{},路由:{},消息内容:{},原因:{}", s1,s2,message,s);
            }
        });
    }
}
