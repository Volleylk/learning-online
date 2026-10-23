package com.xuecheng.media.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RabbitMQ 可靠消息配置：视频转码任务
 *
 * <p>业界标准做法：</p>
 * <ul>
 *   <li>交换机 / 队列 / 消息全部 durable 持久化（broker 重启不丢）</li>
 *   <li>业务队列绑定死信交换机 + 死信路由键：重试次数超限的消息自动进入 DLQ，人工介入，消息不丢失</li>
 *   <li>生产端 confirm（publisher-confirm-type: correlated）+ 消费者手动 ack + 业务状态机幂等</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class MediaRabbitConfig {

    /** 视频转码交换机 */
    public static final String TRANSCODE_EXCHANGE = "media.transcode.exchange";
    /** 视频转码路由键 */
    public static final String TRANSCODE_ROUTING_KEY = "video.transcode";
    /** 视频转码队列 */
    public static final String TRANSCODE_QUEUE = "media.transcode.queue";
    /** 死信交换机 */
    public static final String TRANSCODE_DLQ_EXCHANGE = "media.transcode.exchange.dlq";
    /** 死信路由键 */
    public static final String TRANSCODE_DLQ_ROUTING_KEY = "video.transcode.dlq";
    /** 死信队列 */
    public static final String TRANSCODE_DLQ_QUEUE = "media.transcode.queue.dlq";

    @Bean
    public DirectExchange mediaTranscodeExchange() {
        return new DirectExchange(TRANSCODE_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange mediaTranscodeDlqExchange() {
        return new DirectExchange(TRANSCODE_DLQ_EXCHANGE, true, false);
    }

    @Bean
    public Queue mediaTranscodeQueue() {
        return QueueBuilder.durable(TRANSCODE_QUEUE)
                .withArgument("x-dead-letter-exchange", TRANSCODE_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue mediaTranscodeDlqQueue() {
        return QueueBuilder.durable(TRANSCODE_DLQ_QUEUE).build();
    }

    @Bean
    public Binding mediaTranscodeBinding() {
        return BindingBuilder.bind(mediaTranscodeQueue())
                .to(mediaTranscodeExchange()).with(TRANSCODE_ROUTING_KEY);
    }

    @Bean
    public Binding mediaTranscodeDlqBinding() {
        return BindingBuilder.bind(mediaTranscodeDlqQueue())
                .to(mediaTranscodeDlqExchange()).with(TRANSCODE_DLQ_ROUTING_KEY);
    }
}
