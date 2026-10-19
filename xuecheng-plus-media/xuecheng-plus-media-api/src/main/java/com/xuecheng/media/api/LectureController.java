package com.xuecheng.media.api;

import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.model.po.LectureParseStatus;
import com.xuecheng.media.service.LectureService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 讲义管理接口：教学机构端上传讲义，AI 服务（agent-service）异步解析入库。
 */
@Api(value = "讲义管理接口", tags = "讲义管理接口")
@RestController
@RequestMapping("/api/lecture")
@RequiredArgsConstructor
public class LectureController {

    private final LectureService lectureService;

    /**
     * 上传讲义并触发 AI 解析。
     *
     * 流程：文件入 MinIO(lecture 桶) -> 发 RabbitMQ 消息 ->
     *       Python agent-service 消费消息 -> 下载解析入库 -> 回调 /api/lecture/parse-status
     */
    @ApiOperation("上传讲义并触发AI解析")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RestResponse<String> uploadLecture(
            @RequestPart("file") MultipartFile file,
            @RequestParam("courseId") String courseId,
            @RequestParam("courseName") String courseName,
            @RequestParam(value = "fileId", required = false) String fileId) throws Exception {

        String result = lectureService.uploadLecture(file, courseId, courseName, fileId);
        return RestResponse.success(result);
    }

    /**
     * 接收 AI 服务讲义解析状态回调。
     *
     * 由 Python agent-service 的 LectureConsumer._callback 调用，
     * body: {"fileId": "...", "status": "success|failed", "message": "..."}
     */
    @ApiOperation("接收AI服务讲义解析状态回调")
    @PostMapping("/parse-status")
    public RestResponse<String> parseStatus(@RequestBody Map<String, String> payload) {
        lectureService.recordParseStatus(
                payload.get("fileId"),
                payload.get("status"),
                payload.get("message"));
        return RestResponse.success();
    }

    /**
     * 查询讲义解析状态（持久化 MySQL，media 重启不丢失）。
     *
     * @param fileId 讲义ID（上传接口返回的 fileId）
     * @return 解析状态记录；不存在时 result 为 null
     */
    @ApiOperation("查询讲义解析状态")
    @GetMapping("/parse-status/{fileId}")
    public RestResponse<LectureParseStatus> getParseStatus(@PathVariable String fileId) {
        return RestResponse.success(lectureService.getParseStatus(fileId));
    }

    /**
     * 按课程查询讲义解析状态列表（课程讲义管理页用）。
     *
     * @param courseId 课程ID（可空，空时返回全部，按更新时间倒序）
     * @return 讲义解析状态列表
     */
    @ApiOperation("按课程查询讲义解析状态列表")
    @GetMapping("/parse-status/list")
    public RestResponse<List<LectureParseStatus>> listParseStatus(
            @RequestParam(value = "courseId", required = false) Long courseId) {
        return RestResponse.success(lectureService.listByCourse(courseId));
    }
}
