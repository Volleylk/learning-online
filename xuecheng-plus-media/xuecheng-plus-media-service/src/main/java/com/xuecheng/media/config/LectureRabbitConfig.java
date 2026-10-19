package com.xuecheng.media.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 讲义解析队列声明。
 *
 * 与 Python 端 agent-service 的 app/rabbitmq.py 严格对齐：
 * - exchange: xc.lecture (topic, durable)
 * - queue: xc.lecture.parse (durable)
 * - routing_key: lecture.upload
 * 消息体：{"url": MinIO访问URL, "courseId", "courseName", "fileId"}
 *
 * 生产者确认（publisher confirm）：
 * 配合 bootstrap.yml 的 spring.rabbitmq.publisher-confirm-type=correlated，
 * 每条消息带 CorrelationData 发送，broker 确认送达交换机后回调 setConfirmCallback，
 * 未确认（ack=false）记录 error 日志，避免消息静默丢失。
 */
@Slf4j
@Configuration
public class LectureRabbitConfig {

    @Value("${lecture.rabbit.exchange:xc.lecture}")
    private String exchange;

    @Value("${lecture.rabbit.queue:xc.lecture.parse}")
    private String queue;

    @Value("${lecture.rabbit.routing-key:lecture.upload}")
    private String routingKey;

    @Bean
    public TopicExchange lectureExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    public Queue lectureQueue() {
        return new Queue(queue, true);
    }

    @Bean
    public Binding lectureBinding() {
        return BindingBuilder.bind(lectureQueue()).to(lectureExchange()).with(routingKey);
    }

    /**
     * 定制 RabbitTemplate：设置发布确认回调。
     *
     * 注意：本项目 Spring AMQP 版本较旧（<2.1，RabbitTemplate.setPublisherConfirmType 不可用），
     * 改用连接工厂旧 API CachingConnectionFactory.setPublisherConfirms(true) 开启 publisher confirms；
     * 该方法 2.0+ 全版本可用。开启后 broker 确认送达交换机会回调 setConfirmCallback。
     * 覆盖 Spring Boot 自动配置的同名 bean，media 服务内仅讲义上传使用，不影响其他组件。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            RabbitTemplateConfigurer configurer) {
        if (connectionFactory instanceof CachingConnectionFactory) {
            // 必须在连接创建前开启：通道将执行 confirm.select，broker 确认后触发 ConfirmCallback
            ((CachingConnectionFactory) connectionFactory).setPublisherConfirms(true);
        }
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        configurer.configure(template, connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData != null ? correlationData.getId() : "null";
            if (ack) {
                log.info("[lecture] MQ 消息已确认送达交换机: id={}", id);
            } else {
                log.error("[lecture] MQ 消息发送失败（broker 未确认）: id={}, cause={}", id, cause);
            }
        });
        return template;
    }
}
