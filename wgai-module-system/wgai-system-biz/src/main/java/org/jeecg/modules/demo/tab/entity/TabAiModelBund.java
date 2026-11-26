package org.jeecg.modules.demo.tab.entity;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.jeecg.common.aspect.annotation.Dict;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * @Description: 模型绑定
 * @Author: WGAI
 * @Date:   2024-03-15
 * @Version: V1.0
 */
@Data
@TableName("tab_ai_model_bund")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_ai_model_bund对象", description="模型绑定")
public class TabAiModelBund implements Serializable {
    private static final long serialVersionUID = 1L;

	/**主键*/
	@TableId(type = IdType.ASSIGN_ID)
    @ApiModelProperty(value = "主键")
    private java.lang.String id;
	/**创建人*/
    @ApiModelProperty(value = "创建人")
    private java.lang.String createBy;
	/**创建日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "创建日期")
    private java.util.Date createTime;
	/**更新人*/
    @ApiModelProperty(value = "更新人")
    private java.lang.String updateBy;
	/**更新日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "更新日期")
    private java.util.Date updateTime;
	/**所属部门*/
    @ApiModelProperty(value = "所属部门")
    private java.lang.String sysOrgCode;
	/**模型id*/
	@Excel(name = "模型id", width = 15)
    @ApiModelProperty(value = "模型id")
    private java.lang.String modelId;
	/**模型名称*/
	@Excel(name = "模型名称", width = 15)
    @ApiModelProperty(value = "模型名称")
    @Dict(dictTable = "tab_ai_model", dicCode = "id", dicText = "ai_name")
    private java.lang.String modelName;
	/**输入图片地址*/
	@Excel(name = "输入图片地址", width = 15)
    @ApiModelProperty(value = "输入图片地址")
    private java.lang.String sendUrl;
    /**识别方式：仅保存配置，当前不参与识别流程*/
    @Excel(name = "识别方式", width = 20, dicCode = "video_recognition_mode")
    @ApiModelProperty(value = "识别方式：1视频流识别，2视频流推送重组")
    @Dict(dicCode = "video_recognition_mode")
    private java.lang.String recognitionMode;

    /**Python 推理输入源，与 sendUrl 默认播放地址独立*/
    @ApiModelProperty(value = "推理视频流地址，仅保存配置")
    private java.lang.String inferenceVideoUrl;

    @ApiModelProperty(value = "ZLM 推流地址，仅保存配置")
    private java.lang.String zlmPushUrl;

    @ApiModelProperty(value = "每秒抽取的推理帧数，1至60，建议3")
    private java.lang.Integer inferenceFps;

    @ApiModelProperty(value = "共享 Python 脚本文件路径，仅保存引用，不执行")
    private java.lang.String pythonScriptPath;

    @ApiModelProperty(value = "是否使用 NVIDIA GPU 解码：0关闭，1开启")
    private java.lang.Integer gpuDecode;

    @ApiModelProperty(value = "视频流重组执行方式：java或python，默认java")
    private java.lang.String executionEngine;

	/**保存图片地址*/
	@Excel(name = "保存图片地址", width = 15)
    @ApiModelProperty(value = "保存图片地址")
    private java.lang.String saveUrl;
	/**备注*/
	@Excel(name = "备注", width = 15)
    @ApiModelProperty(value = "备注")
    private java.lang.String remake;
	/**备用*/
	@Excel(name = "备用", width = 15)
    @ApiModelProperty(value = "备用")
    @Dict(dicCode = "identify_status")
    private java.lang.String spaceOne;
	/**备用*/
	@Excel(name = "备用", width = 15)
    @ApiModelProperty(value = "备用")
    private java.lang.String spaceTwo;
	/**备用*/
	@Excel(name = "备用", width = 15)
    @ApiModelProperty(value = "备用")
    private java.lang.String spaceThree;
	/**备用*/
	@Excel(name = "备用", width = 15)
    @ApiModelProperty(value = "备用")
    private java.lang.String spaceFour;
	/**备用*/
	@Excel(name = "备用", width = 15)
    @ApiModelProperty(value = "备用")
    private java.lang.String spaceFive;

    @ApiModelProperty(value = "是否推送")
    private java.lang.String isPush;

    @ApiModelProperty(value = "是否播报")
    private java.lang.String isAudio;

    @ApiModelProperty(value = "推送地址")
    @Dict(dictTable = "tab_ai_subscription", dicCode = "id", dicText = "device_name")
    private java.lang.String pushId;
    @ApiModelProperty(value = "播报地址")
    @Dict(dictTable = "tab_audio_device", dicCode = "id", dicText = "device_name")
    private java.lang.String audioId;



    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否开启区域识别")
    private Integer isBy;
}
