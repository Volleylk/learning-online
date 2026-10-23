package com.xuecheng.content.service.jobhandler;

import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.Channel;
import com.xuecheng.content.feignclient.SearchServiceClient;
import com.xuecheng.content.model.po.CourseIndex;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.content.service.CoursePublishService;
import com.xuecheng.messagesdk.model.po.MqMessage;
import com.xuecheng.messagesdk.service.MqMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.BeanUtils;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * 课程发布 MQ 消费者（由 xxl-job CoursePublishTask 迁移而来）
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li><b>手动 ack</b>：三段业务全部成功才 basicAck</li>
 *   <li><b>幂等</b>：mq_message 表 stage1/stage2 状态机（原样保留），消息重复投递不会重复静态化/写索引</li>
 *   <li><b>失败重试</b>：业务失败 executeNum+1，未达上限 basicNack(requeue=true) 重新入队重试</li>
 *   <li><b>死信队列</b>：executeNum≥3 时 basicNack(requeue=false)，消息进入 content.publish.queue.dlq 人工介入</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoursePublishTask {

    /** 重试上限：与 mq_message.execute_num 配合，最多尝试 3 次 */
    private static final int MAX_EXECUTE_NUM = 3;

    private final CoursePublishService coursePublishService;

    private final SearchServiceClient searchServiceClient;

    private final MqMessageService mqMessageService;

    @RabbitListener(queues = "content.publish.queue", ackMode = "MANUAL")
    public void consume(String messageBody, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        Long messageId = null;
        try {
            JSONObject body = JSONObject.parseObject(messageBody);
            messageId = body.getLong("messageId");
            MqMessage mqMessage = mqMessageService.getById(messageId);
            if (mqMessage == null) {
                // 消息记录不存在（可能已完成并移入历史表）：确认丢弃
                log.warn("发布消息记录不存在，确认丢弃, messageId:{}", messageId);
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.debug("开始处理课程发布消息:{}", mqMessage);
            boolean success = execute(mqMessage);
            if (success) {
                // 完成：状态置 1、移入历史表、删除消息表记录
                mqMessageService.completed(mqMessage.getId());
                channel.basicAck(deliveryTag, false);
                log.info("课程发布消息处理完成, messageId:{}", mqMessage.getId());
            } else {
                failTask(channel, deliveryTag, mqMessage);
            }
        } catch (Exception e) {
            log.error("课程发布消费异常, messageId:{}, 异常:{}", messageId, e.getMessage(), e);
            if (messageId == null) {
                // 消息体非法：确认丢弃，防止毒丸消息死循环
                channel.basicAck(deliveryTag, false);
            } else {
                // 业务异常同样计入重试次数：未达上限重新入队，达到上限进死信队列，防止无限重投风暴
                MqMessage mqMessage = mqMessageService.getById(messageId);
                if (mqMessage == null) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    failTask(channel, deliveryTag, mqMessage);
                }
            }
        }
    }

    /** 失败重试控制：executeNum+1，未达上限重新入队，达到上限进死信队列 */
    private void failTask(Channel channel, long deliveryTag, MqMessage mqMessage) throws Exception {
        int executeNum = (mqMessage.getExecuteNum() == null ? 0 : mqMessage.getExecuteNum()) + 1;
        MqMessage update = new MqMessage();
        update.setId(mqMessage.getId());
        update.setExecuteNum(executeNum);
        mqMessageService.updateById(update);
        if (executeNum >= MAX_EXECUTE_NUM) {
            log.warn("课程发布消息重试次数已达上限，进入死信队列, messageId:{}, executeNum:{}",
                    mqMessage.getId(), executeNum);
            channel.basicNack(deliveryTag, false, false);
        } else {
            log.warn("课程发布消息处理失败，重新入队重试, messageId:{}, executeNum:{}",
                    mqMessage.getId(), executeNum);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    //课程发布任务处理
    public boolean execute(MqMessage mqMessage) {
        //获取消息相关的业务信息
        String businessKey1 = mqMessage.getBusinessKey1();
        long courseId = Integer.parseInt(businessKey1);
        //课程静态化
        generateCourseHtml(mqMessage, courseId);
        //课程索引
        saveCourseIndex(mqMessage, courseId);
        //课程缓存
        saveCourseCache(mqMessage, courseId);
        return true;
    }

    //生成课程静态化页面并上传至文件系统
    public void generateCourseHtml(MqMessage mqMessage, long courseId) {

        log.debug("开始进行课程静态化,课程id:{}", courseId);
        //消息id
        Long id = mqMessage.getId();
        //消息幂等性处理
        int stageOne = mqMessageService.getStageOne(id);
        if (stageOne > 0) {
            log.debug("课程静态化已处理直接返回，课程id:{}", courseId);
            return;
        }
        //生成静态化页面
        File file = coursePublishService.generateCourseHtml(courseId);
        //上传静态化页面
        if (file != null) {
            coursePublishService.uploadCourseHtml(courseId, file);
        }
        //保存第一阶段状态
        mqMessageService.completedStageOne(id);

    }

    //将课程信息缓存至redis
    public void saveCourseCache(MqMessage mqMessage, long courseId) {
        log.debug("将课程信息缓存至redis,课程id:{}", courseId);
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    //保存课程索引信息
    public void saveCourseIndex(MqMessage mqMessage, long courseId) {
        log.debug("保存课程索引信息,课程id:{}", courseId);
        //消息id
        Long id = mqMessage.getId();
        //第二阶段幂等性判断
        int stageTwo = mqMessageService.getStageTwo(id);
        if (stageTwo > 0) {
            log.debug("课程索引已处理直接返回，课程id:{}", courseId);
            return;
        }
        //查询课程发布信息
        CoursePublish coursePublish = coursePublishService.getCoursePublish(courseId);
        if (coursePublish == null) {
            log.debug("课程发布信息为空，课程id:{}", courseId);
            return;
        }
        //拷贝为索引对象
        CourseIndex courseIndex = new CourseIndex();
        BeanUtils.copyProperties(coursePublish, courseIndex);
        //远程调用搜索服务添加课程索引
        Boolean result = searchServiceClient.add(courseIndex);
        if (result == null || !result) {
            com.xuecheng.base.exception.XueChengPlusException.cast("添加课程索引失败");
        }
        //保存第二阶段状态
        mqMessageService.completedStageTwo(id);
    }

}
