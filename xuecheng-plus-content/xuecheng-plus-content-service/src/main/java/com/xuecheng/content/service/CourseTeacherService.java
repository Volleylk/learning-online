package com.xuecheng.content.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xuecheng.content.model.dto.AddCourseTeacherDto;
import com.xuecheng.content.model.dto.EditCourseTeacherDto;
import com.xuecheng.content.model.po.CourseTeacher;

import java.util.List;

public interface CourseTeacherService extends IService<CourseTeacher> {

    List<CourseTeacher>  list(Long id);

    CourseTeacher save(AddCourseTeacherDto courseTeacherDto);

    CourseTeacher updateCourseTeacher(EditCourseTeacherDto editcourseTeacherDto);

    void deleteCourseTeacher(Long courseId, Long id);
}
