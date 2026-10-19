package com.xuecheng.content.model.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
@ToString
public class AddCourseTeacherDto {
    /**
     * 课程标识
     */
    @NotNull(message = "课程标识不能为空")
    private Long courseId;

    /**
     * 教师标识
     */
    @NotEmpty(message = "教师标识不能为空")
    private String teacherName;

    /**
     * 教师职位
     */
    @NotEmpty(message = "教师职位不能为空")
    private String position;

    /**
     * 教师简介
     */
    @NotEmpty(message = "教师简介不能为空")
    private String introduction;
}
