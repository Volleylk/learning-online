package com.xuecheng.media.service.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xuecheng.media.config.MediaRabbitConfig;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.po.MediaProcess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 视频转码任务兜底补偿（替代原 xxl-job 轮询补偿，使用 Spring 自带调度）
 *
 * <p>场景：消费者处理过程中进程崩溃，消息已确认或丢失、任务卡在 status=4（处理中）。</p>
 * <p>兜底：定时扫描 status=4 且超过 30 分钟未完成的任务，乐观重置为 status=1 并重新投递 MQ 消息，保证任务最终一定被处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaProcessCompensateTask {

    /** 卡死判定：处理中超过 30 分钟视为异常 */
    private static final long STUCK_MINUTES = 30;

    private final MediaProcessMapper mediaProcessMapper;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 600_000)
    public void compensate() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(STUCK_MINUTES);
        List<MediaProcess> stuckList = mediaProcessMapper.selectList(
                new LambdaQueryWrapper<MediaProcess>()
                        .eq(MediaProcess::getStatus, "4")
                        .lt(MediaProcess::getCreateDate, deadline));
        if (stuckList.isEmpty()) {
            return;
        }
        log.warn("发现卡死的视频转码任务 {} 条，开始重置并重新投递", stuckList.size());
        for (MediaProcess mp : stuckList) {
            // 乐观重置：仅当仍是 status=4 时才重置，防止并发重复补偿
            int reset = mediaProcessMapper.resetStatus(mp.getId());
            if (reset > 0) {
                rabbitTemplate.convertAndSend(MediaRabbitConfig.TRANSCODE_EXCHANGE,
                        MediaRabbitConfig.TRANSCODE_ROUTING_KEY, String.valueOf(mp.getId()));
                log.warn("转码任务卡死已重置并重新投递, taskId:{}, fileId:{}", mp.getId(), mp.getFileId());
            }
        }
    }
}
