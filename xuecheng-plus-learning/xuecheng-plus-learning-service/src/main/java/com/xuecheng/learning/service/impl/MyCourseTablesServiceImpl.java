package com.xuecheng.learning.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.content.model.po.CoursePublish;
import com.xuecheng.learning.feignclient.ContentServiceClient;
import com.xuecheng.learning.mapper.XcChooseCourseMapper;
import com.xuecheng.learning.mapper.XcCourseTablesMapper;
import com.xuecheng.learning.model.dto.MyCourseTableParams;
import com.xuecheng.learning.model.dto.XcChooseCourseDto;
import com.xuecheng.learning.model.dto.XcCourseTablesDto;
import com.xuecheng.learning.model.po.XcChooseCourse;
import com.xuecheng.learning.model.po.XcCourseTables;
import com.xuecheng.learning.service.MyCourseTablesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 我的课程表 service 实现
 */
@Slf4j
@Service
public class MyCourseTablesServiceImpl implements MyCourseTablesService {

    @Autowired
    XcChooseCourseMapper xcChooseCourseMapper;

    @Autowired
    XcCourseTablesMapper xcCourseTablesMapper;

    @Autowired
    ContentServiceClient contentServiceClient;

    @Transactional
    @Override
    public XcChooseCourseDto addChooseCourse(String userId, Long courseId) {
        // 远程查询课程发布信息
        CoursePublish coursepublish = contentServiceClient.getCoursepublish(courseId);
        if (coursepublish == null) {
            XueChengPlusException.cast("课程信息不存在");
        }
        String charge = coursepublish.getCharge();
        XcChooseCourse chooseCourse;
        if ("201000".equals(charge)) {
            // 免费课程：写选课记录 + 我的课程表
            chooseCourse = addFreeCourse(userId, coursepublish);
            addCourseTables(chooseCourse);
        } else {
            // 收费课程：只写选课记录，待支付
            chooseCourse = addChargeCourse(userId, coursepublish);
        }
        XcChooseCourseDto dto = new XcChooseCourseDto();
        BeanUtils.copyProperties(chooseCourse, dto);
        XcCourseTablesDto statusDto = getLearningStatus(userId, courseId);
        dto.setLearnStatus(statusDto.getLearnStatus());
        return dto;
    }

    @Override
    public XcCourseTablesDto getLearningStatus(String userId, Long courseId) {
        XcCourseTables courseTables = getXcCourseTables(userId, courseId);
        XcCourseTablesDto dto = new XcCourseTablesDto();
        if (courseTables == null) {
            dto.setLearnStatus("702002");
            return dto;
        }
        BeanUtils.copyProperties(courseTables, dto);
        boolean expired = courseTables.getValidtimeEnd().isBefore(LocalDateTime.now());
        dto.setLearnStatus(expired ? "702003" : "702001");
        return dto;
    }

    /**
     * 添加免费课程到选课记录表
     */
    public XcChooseCourse addFreeCourse(String userId, CoursePublish coursepublish) {
        LambdaQueryWrapper<XcChooseCourse> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XcChooseCourse::getUserId, userId)
                .eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getOrderType, "700001")
                .eq(XcChooseCourse::getStatus, "701001");
        List<XcChooseCourse> list = xcChooseCourseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.get(0);
        }
        XcChooseCourse c = new XcChooseCourse();
        c.setCourseId(coursepublish.getId());
        c.setCourseName(coursepublish.getName());
        c.setCoursePrice(0f);
        c.setUserId(userId);
        c.setCompanyId(coursepublish.getCompanyId());
        c.setOrderType("700001");
        c.setCreateDate(LocalDateTime.now());
        c.setStatus("701001");
        c.setValidDays(365);
        c.setValidtimeStart(LocalDateTime.now());
        c.setValidtimeEnd(LocalDateTime.now().plusDays(365));
        xcChooseCourseMapper.insert(c);
        return c;
    }

    /**
     * 添加收费课程到选课记录表（待支付）
     */
    public XcChooseCourse addChargeCourse(String userId, CoursePublish coursepublish) {
        LambdaQueryWrapper<XcChooseCourse> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(XcChooseCourse::getUserId, userId)
                .eq(XcChooseCourse::getCourseId, coursepublish.getId())
                .eq(XcChooseCourse::getOrderType, "700002")
                .eq(XcChooseCourse::getStatus, "701002");
        List<XcChooseCourse> list = xcChooseCourseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.get(0);
        }
        XcChooseCourse c = new XcChooseCourse();
        c.setCourseId(coursepublish.getId());
        c.setCourseName(coursepublish.getName());
        c.setCoursePrice(coursepublish.getPrice());
        c.setUserId(userId);
        c.setCompanyId(coursepublish.getCompanyId());
        c.setOrderType("700002");
        c.setCreateDate(LocalDateTime.now());
        c.setStatus("701002");
        c.setValidDays(coursepublish.getValidDays());
        c.setValidtimeStart(LocalDateTime.now());
        c.setValidtimeEnd(LocalDateTime.now().plusDays(coursepublish.getValidDays()));
        xcChooseCourseMapper.insert(c);
        return c;
    }

    /**
     * 选课成功后加入我的课程表
     */
    public XcCourseTables addCourseTables(XcChooseCourse chooseCourse) {
        if (!"701001".equals(chooseCourse.getStatus())) {
            XueChengPlusException.cast("选课未成功，无法添加到课程表");
        }
        XcCourseTables existing = getXcCourseTables(chooseCourse.getUserId(), chooseCourse.getCourseId());
        if (existing != null) {
            return existing;
        }
        XcCourseTables t = new XcCourseTables();
        t.setChooseCourseId(chooseCourse.getId());
        t.setUserId(chooseCourse.getUserId());
        t.setCourseId(chooseCourse.getCourseId());
        t.setCompanyId(chooseCourse.getCompanyId());
        t.setCourseName(chooseCourse.getCourseName());
        t.setCreateDate(LocalDateTime.now());
        t.setValidtimeStart(chooseCourse.getValidtimeStart());
        t.setValidtimeEnd(chooseCourse.getValidtimeEnd());
        t.setCourseType(chooseCourse.getOrderType());
        xcCourseTablesMapper.insert(t);
        return t;
    }

    private XcCourseTables getXcCourseTables(String userId, Long courseId) {
        return xcCourseTablesMapper.selectOne(
                new LambdaQueryWrapper<XcCourseTables>()
                        .eq(XcCourseTables::getUserId, userId)
                        .eq(XcCourseTables::getCourseId, courseId));
    }

    @Transactional
    @Override
    public boolean saveChooseCourseSuccess(String chooseCourseId) {
        XcChooseCourse chooseCourse = xcChooseCourseMapper.selectById(chooseCourseId);
        if (chooseCourse == null) {
            log.debug("收到支付结果通知，选课记录不存在:{}", chooseCourseId);
            return false;
        }
        if ("701001".equals(chooseCourse.getStatus())) {
            return true;
        }
        XcChooseCourse update = new XcChooseCourse();
        update.setId(chooseCourse.getId());
        update.setStatus("701001");
        xcChooseCourseMapper.update(update,
                new LambdaQueryWrapper<XcChooseCourse>().eq(XcChooseCourse::getId, chooseCourseId));
        chooseCourse.setStatus("701001");
        addCourseTables(chooseCourse);
        return true;
    }

    @Override
    public PageResult<XcCourseTables> mycoursetables(MyCourseTableParams params) {
        Page<XcCourseTables> page = new Page<>(params.getPage(), params.getSize());
        LambdaQueryWrapper<XcCourseTables> wrapper = new LambdaQueryWrapper<XcCourseTables>()
                .eq(XcCourseTables::getUserId, params.getUserId())
                .orderByDesc(XcCourseTables::getCreateDate);
        Page<XcCourseTables> result = xcCourseTablesMapper.selectPage(page, wrapper);
        return new PageResult<>(result.getRecords(), result.getTotal(), params.getPage(), params.getSize());
    }
}
