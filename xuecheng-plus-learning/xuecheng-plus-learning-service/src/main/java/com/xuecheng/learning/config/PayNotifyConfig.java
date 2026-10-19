package com.xuecheng.learning.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PayNotifyConfig {

    public static final String PAYNOTIFY_EXCHANGE_FANOUT = "paynotify_exchange_fanout";
    public static final String MESSAGE_TYPE = "payresult_notify";
    public static final String PAYNOTIFY_QUEUE = "paynotify_queue";

    @Bean(PAYNOTIFY_EXCHANGE_FANOUT)
    public FanoutExchange paynotify_exchange_fanout() {
        return new FanoutExchange(PAYNOTIFY_EXCHANGE_FANOUT, true, false);
    }

    @Bean(PAYNOTIFY_QUEUE)
    public Queue paynotify_queue() {
        return QueueBuilder.durable(PAYNOTIFY_QUEUE).build();
    }

    @Bean
    public Binding binding_paynotify_queue(@Qualifier(PAYNOTIFY_QUEUE) Queue queue,
                                           @Qualifier(PAYNOTIFY_EXCHANGE_FANOUT) FanoutExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange);
    }
}
