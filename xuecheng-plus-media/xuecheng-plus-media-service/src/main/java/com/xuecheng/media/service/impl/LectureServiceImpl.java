package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuecheng.media.mapper.LectureParseStatusMapper;
import com.xuecheng.media.model.po.LectureParseStatus;
import com.xuecheng.media.service.LectureService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 讲义上传实现：文件写入 MinIO 的 lecture 桶，再发 RabbitMQ 消息。
 *
 * 消息体字段与 Python 端 agent-service app/rabbitmq.py 消费逻辑对齐：
 * {url, courseId, courseName, fileId}（Python 端同时兼容 course_id/file_id 命名，
 * 并支持从 url 解析 bucket/object）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LectureServiceImpl implements LectureService {

    private final MinioClient minioClient;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final LectureParseStatusMapper lectureParseStatusMapper;

    /** MinIO 访问地址（构建文件URL用），如 http://192.168.101.65:9000 */
    @Value("${minio.endpoint}")
    private String minioEndpoint;

    /** 讲义文件桶，须与 agent-service .env 的 MINIO_BUCKET 一致 */
    @Value("${lecture.minio.bucket:lecture}")
    private String bucket;

    /** 交换机/路由键，须与 agent-service .env 的 RABBIT_EXCHANGE / RABBIT_ROUTING_KEY 一致 */
    @Value("${lecture.rabbit.exchange:xc.lecture}")
    private String exchange;

    @Value("${lecture.rabbit.routing-key:lecture.upload}")
    private String routingKey;

    @Override
    public void recordParseStatus(String fileId, String status, String message) {
        if (!StringUtils.hasText(fileId)) {
            log.warn("收到讲义状态回调但缺少 fileId: status={}, message={}", status, message);
            return;
        }
        // 持久化到 MySQL：不存在则插入，存在则更新（media 重启不丢失）
        LectureParseStatus st = lectureParseStatusMapper.selectById(fileId);
        if (st == null) {
            st = new LectureParseStatus();
            st.setFileId(fileId);
            st.setStatus(status == null ? "" : status);
            st.setMessage(message == null ? "" : message);
            lectureParseStatusMapper.insert(st);
        } else {
            st.setStatus(status == null ? "" : status);
            st.setMessage(message == null ? "" : message);
            // 手动刷新更新时间：MyBatis-Plus updateById 默认忽略 null 字段，
            // 不 set 的话 update_time 不会随回调更新；显式 +8 时区与 DB CURRENT_TIMESTAMP 对齐
            st.setUpdateTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
            lectureParseStatusMapper.updateById(st);
        }
        log.info("收到讲义解析状态回调: fileId={}, status={}, message={}", fileId, status, message);
    }

    @Override
    public LectureParseStatus getParseStatus(String fileId) {
        return lectureParseStatusMapper.selectById(fileId);
    }

    @Override
    public List<LectureParseStatus> listByCourse(Long courseId) {
        LambdaQueryWrapper<LectureParseStatus> wrapper = new LambdaQueryWrapper<>();
        if (courseId != null && courseId > 0) {
            // courseId 存的是 String，按字符串等值匹配
            wrapper.eq(LectureParseStatus::getCourseId, String.valueOf(courseId));
        }
        wrapper.orderByDesc(LectureParseStatus::getUpdateTime);
        return lectureParseStatusMapper.selectList(wrapper);
    }

    @Override
    public String uploadLecture(MultipartFile file, String courseId, String courseName, String fileId) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("讲义文件不能为空");
        }
        if (!StringUtils.hasText(courseId)) {
            throw new IllegalArgumentException("courseId 不能为空");
        }
        if (!StringUtils.hasText(fileId)) {
            fileId = UUID.randomUUID().toString();
        }
        String filename = StringUtils.hasText(file.getOriginalFilename())
                ? file.getOriginalFilename() : "lecture.pdf";
        // 对象名：fileId_文件名，避免把 courseId 拼进路径（防特殊字符破坏对象层级）
        String objectName = fileId + "_" + filename;

        // 确保桶存在（幂等）
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("已创建 MinIO 桶: {}", bucket);
        }

        // 上传到 MinIO
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        String url = minioEndpoint + "/" + bucket + "/" + objectName;
        log.info("讲义已上传 MinIO: url={}", url);

        // 落库初始状态 processing（持久化 MySQL，media 重启不丢失；先落库再发消息，
        // 避免 agent 秒回回调时状态行还不存在）
        LectureParseStatus initStatus = new LectureParseStatus();
        initStatus.setFileId(fileId);
        initStatus.setCourseId(courseId);
        initStatus.setCourseName(courseName == null ? "" : courseName);
        initStatus.setSourceUrl(url);
        initStatus.setStatus("processing");
        initStatus.setMessage("已进入解析队列");
        lectureParseStatusMapper.insert(initStatus);
        log.info("讲义解析状态已落库: fileId={}, status=processing", fileId);

        // 发送 RabbitMQ 消息（String 消息体，Python 端按 UTF-8 JSON 解析）
        // 带 CorrelationData：broker 确认后 LectureRabbitConfig 的 ConfirmCallback 记录日志，
        // 未确认会打 error 日志，避免消息静默丢失。
        Map<String, Object> msg = new HashMap<>();
        msg.put("url", url);
        msg.put("courseId", courseId);
        msg.put("courseName", courseName == null ? "" : courseName);
        msg.put("fileId", fileId);
        String msgId = UUID.randomUUID().toString();
        CorrelationData cd = new CorrelationData(msgId);
        rabbitTemplate.convertAndSend(exchange, routingKey, objectMapper.writeValueAsString(msg), cd);
        log.info("讲义解析消息已发送: exchange={}, routingKey={}, courseId={}, fileId={}, msgId={}",
                exchange, routingKey, courseId, fileId, msgId);

        return "上传成功，AI 解析任务已进入队列，fileId=" + fileId;
    }
}
