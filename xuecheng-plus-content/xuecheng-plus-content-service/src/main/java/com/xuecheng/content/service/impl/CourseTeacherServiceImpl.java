package com.xuecheng.content.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.content.mapper.CourseTeacherMapper;
import com.xuecheng.content.model.dto.AddCourseTeacherDto;
import com.xuecheng.content.model.dto.EditCourseTeacherDto;
import com.xuecheng.content.model.po.CourseTeacher;
import com.xuecheng.content.service.CourseTeacherService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseTeacherServiceImpl extends ServiceImpl<CourseTeacherMapper, CourseTeacher>
        implements CourseTeacherService {

    private final CourseTeacherMapper courseTeacherMapper;

    @Override
    public List<CourseTeacher> list(Long id) {
        return lambdaQuery().eq(CourseTeacher::getCourseId, id).list();
    }

    @Override
    public CourseTeacher save(AddCourseTeacherDto addCourseTeacherDto) {
        CourseTeacher courseTeacher = new CourseTeacher();
        BeanUtils.copyProperties(addCourseTeacherDto, courseTeacher);
        int insert = courseTeacherMapper.insert(courseTeacher);
        if (insert <= 0) {
            XueChengPlusException.cast("新增课程教师失败");
        }
        // 插入后，courseTeacher已经包含id，直接返回，不需要二次查询！
        return courseTeacher;
    }


    @Override
    public CourseTeacher updateCourseTeacher(EditCourseTeacherDto editcourseTeacherDto) {
        CourseTeacher courseTeacher = new CourseTeacher();
        BeanUtils.copyProperties(editcourseTeacherDto, courseTeacher);
        courseTeacherMapper.updateById(courseTeacher);
        return courseTeacher;
    }

    @Override
    @Transactional
    public void deleteCourseTeacher(Long courseId, Long id) {
        LambdaQueryWrapper<CourseTeacher> wrapper = new LambdaQueryWrapper<CourseTeacher>()
                .eq(CourseTeacher::getCourseId, courseId)
                .eq(CourseTeacher::getId, id);
        int i = courseTeacherMapper.delete(wrapper);
        if (i <= 0)
            XueChengPlusException.cast("删除课程教师失败");
    }
}
