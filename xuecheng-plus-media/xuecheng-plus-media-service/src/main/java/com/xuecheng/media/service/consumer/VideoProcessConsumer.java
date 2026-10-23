package com.xuecheng.media.service.consumer;

import com.rabbitmq.client.Channel;
import com.xuecheng.base.utils.Mp4VideoUtil;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileProcessService;
import com.xuecheng.media.service.MediaFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * 视频转码 MQ 消费者（由 xxl-job VideoTask 迁移而来）
 *
 * <p>可靠性设计：</p>
 * <ul>
 *   <li><b>手动 ack</b>：业务处理成功才 basicAck，broker 不会误删消息</li>
 *   <li><b>幂等抢占</b>：startTask 乐观锁（status 1/3 → 4, fail_count&lt;3），多实例/重复投递天然去重</li>
 *   <li><b>失败重试</b>：转码失败保存 status=3 并 fail_count+1，未达上限 basicNack(requeue=true) 重新入队重试</li>
 *   <li><b>死信队列</b>：fail_count≥3 时 basicNack(requeue=false)，消息进入 media.transcode.queue.dlq 人工介入</li>
 *   <li><b>毒丸防护</b>：消息体非法时确认丢弃，避免死循环</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessConsumer {

    /** 重试上限：与 media_process.fail_count 配合，最多尝试 3 次 */
    private static final int MAX_FAIL_COUNT = 3;

    private final MediaFileService mediaFileService;
    private final MediaFileProcessService mediaFileProcessService;
    private final MediaProcessMapper mediaProcessMapper;

    @Value("${videoprocess.ffmpegpath}")
    private String ffmpegpath;

    @RabbitListener(queues = "media.transcode.queue", ackMode = "MANUAL")
    public void transcode(String messageBody, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long taskId = null;
        try {
            taskId = Long.parseLong(messageBody.trim());
            MediaProcess mediaProcess = mediaProcessMapper.selectById(taskId);
            if (mediaProcess == null) {
                log.warn("转码任务不存在，确认丢弃消息, taskId:{}", taskId);
                channel.basicAck(deliveryTag, false);
                return;
            }
            // 幂等抢占：status 1/3 → 4；抢占失败说明已被其它实例处理或重试次数已尽，确认丢弃
            if (!mediaFileProcessService.startTask(taskId)) {
                log.warn("转码任务抢占失败（已处理或重试次数已尽），确认丢弃, taskId:{}, status:{}, failCount:{}",
                        taskId, mediaProcess.getStatus(), mediaProcess.getFailCount());
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.debug("开始执行视频转码任务:{}", mediaProcess);

            String bucket = mediaProcess.getBucket();
            String filePath = mediaProcess.getFilePath();
            String fileId = mediaProcess.getFileId();

            // 1. 从 MinIO 下载待处理文件
            File originalFile = mediaFileService.downloadFileFromMinIO(bucket, filePath);
            if (originalFile == null) {
                log.error("下载待处理文件失败, 路径:{}{}", bucket, filePath);
                failTask(channel, deliveryTag, taskId, fileId, "下载待处理文件失败");
                return;
            }
            // 2. 创建临时 mp4 文件并执行 ffmpeg 转码
            File mp4File = File.createTempFile("mp4", ".mp4");
            String result;
            try {
                result = new Mp4VideoUtil(ffmpegpath, originalFile.getAbsolutePath(),
                        mp4File.getName(), mp4File.getAbsolutePath()).generateMp4();
            } catch (Exception e) {
                log.error("视频转码异常, taskId:{}, 异常:{}", taskId, e.getMessage(), e);
                result = "转码异常: " + e.getMessage();
            }
            if (!"success".equals(result)) {
                log.error("视频转码失败, 视频地址:{}, 原因:{}", bucket + filePath, result);
                failTask(channel, deliveryTag, taskId, fileId, result);
                return;
            }
            // 3. 上传转码后的 mp4 到 MinIO 并更新任务状态
            String objectName = getFilePath(fileId, ".mp4");
            String url = "/" + bucket + "/" + objectName;
            try {
                mediaFileService.addMediaFilesToMinIO(mp4File.getAbsolutePath(), "video/mp4", bucket, objectName);
                mediaFileProcessService.saveProcessFinishStatus(taskId, "2", fileId, url, null);
                log.info("视频转码成功, taskId:{}, url:{}", taskId, url);
                channel.basicAck(deliveryTag, false);
            } catch (Exception e) {
                log.error("上传转码视频失败, taskId:{}, 异常:{}", taskId, e.getMessage(), e);
                failTask(channel, deliveryTag, taskId, fileId, "上传转码视频失败: " + e.getMessage());
            }
        } catch (Exception e) {
            log.error("视频转码消费异常, taskId:{}, 异常:{}", taskId, e.getMessage(), e);
            if (taskId == null) {
                // 消息体非法：确认丢弃，防止毒丸消息无限重试
                channel.basicAck(deliveryTag, false);
            } else {
                // 未知异常同样计入失败重试：未达上限重新入队，达到上限进死信队列，防止无限重投风暴
                MediaProcess mediaProcess = mediaProcessMapper.selectById(taskId);
                if (mediaProcess == null) {
                    channel.basicAck(deliveryTag, false);
                } else {
                    failTask(channel, deliveryTag, taskId, mediaProcess.getFileId(),
                            "消费异常: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 转码失败处理：保存失败状态（fail_count+1），未达上限重新入队重试，达到上限进死信队列
     */
    private void failTask(Channel channel, long deliveryTag, Long taskId, String fileId, String errorMsg) throws IOException {
        mediaFileProcessService.saveProcessFinishStatus(taskId, "3", fileId, null, errorMsg);
        MediaProcess after = mediaProcessMapper.selectById(taskId);
        if (after != null && after.getFailCount() >= MAX_FAIL_COUNT) {
            log.warn("转码任务重试次数已达上限，进入死信队列, taskId:{}, failCount:{}", taskId, after.getFailCount());
            channel.basicNack(deliveryTag, false, false);
        } else {
            log.warn("转码任务失败，重新入队重试, taskId:{}, failCount:{}",
                    taskId, after == null ? -1 : after.getFailCount());
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 根据文件 MD5 和扩展名构建 MinIO 存储路径（与原 VideoTask 一致）
     */
    private String getFilePath(String fileMd5, String fileExt) {
        return fileMd5.charAt(0) + "/" + fileMd5.charAt(1) + "/" + fileMd5 + fileExt;
    }
}
