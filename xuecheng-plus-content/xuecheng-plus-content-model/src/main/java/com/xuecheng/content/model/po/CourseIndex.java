package com.xuecheng.content.model.po;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 课程索引信息（与搜索工程 CourseIndex 字段保持一致）
 * </p>
 */
@Data
public class CourseIndex implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long companyId;
    private String companyName;
    private String name;
    private String users;
    private String tags;
    private String mt;
    private String mtName;
    private String st;
    private String stName;
    private String grade;
    private String teachmode;
    private String pic;
    private String description;

    @JSONField(format = "yyyy-MM-dd HH:mm:ss")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createDate;

    private String status;
    private String remark;
    private String charge;
    private Float price;
    private Float originalPrice;
    private Integer validDays;
}
