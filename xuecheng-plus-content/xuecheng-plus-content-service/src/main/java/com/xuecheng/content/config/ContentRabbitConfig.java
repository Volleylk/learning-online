package com.xuecheng.content.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ 可靠消息配置：课程发布 / 静态化任务
 *
 * <p>与 media 转码同款业界标准：交换机/队列/消息 durable；业务队列绑定死信交换机，
 * 重试超限消息自动进入 DLQ 人工介入；生产 confirm + 消费者手动 ack + mq_message 表 stage 幂等。</p>
 */
@Configuration
@EnableScheduling
public class ContentRabbitConfig {

    /** 课程发布交换机 */
    public static final String PUBLISH_EXCHANGE = "content.publish.exchange";
    /** 课程发布路由键 */
    public static final String PUBLISH_ROUTING_KEY = "course.publish";
    /** 课程发布队列 */
    public static final String PUBLISH_QUEUE = "content.publish.queue";
    /** 死信交换机 */
    public static final String PUBLISH_DLQ_EXCHANGE = "content.publish.exchange.dlq";
    /** 死信路由键 */
    public static final String PUBLISH_DLQ_ROUTING_KEY = "course.publish.dlq";
    /** 死信队列 */
    public static final String PUBLISH_DLQ_QUEUE = "content.publish.queue.dlq";

    @Bean
    public DirectExchange contentPublishExchange() {
        return new DirectExchange(PUBLISH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange contentPublishDlqExchange() {
        return new DirectExchange(PUBLISH_DLQ_EXCHANGE, true, false);
    }

    @Bean
    public Queue contentPublishQueue() {
        return QueueBuilder.durable(PUBLISH_QUEUE)
                .withArgument("x-dead-letter-exchange", PUBLISH_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", PUBLISH_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue contentPublishDlqQueue() {
        return QueueBuilder.durable(PUBLISH_DLQ_QUEUE).build();
    }

    @Bean
    public Binding contentPublishBinding() {
        return BindingBuilder.bind(contentPublishQueue())
                .to(contentPublishExchange()).with(PUBLISH_ROUTING_KEY);
    }

    @Bean
    public Binding contentPublishDlqBinding() {
        return BindingBuilder.bind(contentPublishDlqQueue())
                .to(contentPublishDlqExchange()).with(PUBLISH_DLQ_ROUTING_KEY);
    }
}
