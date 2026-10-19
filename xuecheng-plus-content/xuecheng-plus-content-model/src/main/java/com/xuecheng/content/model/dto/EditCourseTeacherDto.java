package com.xuecheng.content.model.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 *编辑教师
 */
@Data
@ApiModel(value="EditCourseDto", description="修改课程基本信息")
public class EditCourseTeacherDto extends AddCourseTeacherDto {

    @ApiModelProperty(value ="教师id", required = true)
    private Long id;

}