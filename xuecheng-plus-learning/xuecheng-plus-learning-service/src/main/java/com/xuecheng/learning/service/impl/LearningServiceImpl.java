package com.xuecheng.learning.service.impl;

import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.learning.feignclient.ContentServiceClient;
import com.xuecheng.learning.feignclient.MediaServiceClient;
import com.xuecheng.learning.model.dto.XcCourseTablesDto;
import com.xuecheng.learning.service.LearningService;
import com.xuecheng.learning.service.MyCourseTablesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class LearningServiceImpl implements LearningService {

    @Autowired
    ContentServiceClient contentServiceClient;

    @Autowired
    MediaServiceClient mediaServiceClient;

    @Autowired
    MyCourseTablesService myCourseTablesService;

    @Override
    public RestResponse<String> getVideo(String userId, Long courseId, Long teachplanId, String mediaId) {
        CoursePublish coursepublish = contentServiceClient.getCoursepublish(courseId);
        if (coursepublish == null) {
            XueChengPlusException.cast("课程不存在");
        }
        String charge = coursepublish.getCharge();
        // 免费课程直接放行
        if ("201000".equals(charge)) {
            return mediaServiceClient.getPlayUrlByMediaId(mediaId);
        }
        if (userId == null) {
            return RestResponse.validfail("请登录后学习");
        }
        XcCourseTablesDto status = myCourseTablesService.getLearningStatus(userId, courseId);
        if ("702001".equals(status.getLearnStatus())) {
            return mediaServiceClient.getPlayUrlByMediaId(mediaId);
        } else if ("702002".equals(status.getLearnStatus())) {
            return RestResponse.validfail("无法观看，没有选课或选课后没有支付");
        } else {
            return RestResponse.validfail("课程已过期，请续期或重新购买");
        }
    }
}
