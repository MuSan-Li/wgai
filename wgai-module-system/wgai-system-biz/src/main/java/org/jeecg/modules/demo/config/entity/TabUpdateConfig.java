package org.jeecg.modules.demo.config.entity;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
 * @Description: 更新上传配置
 * @Author: jeecg-boot
 * @Date:   2026-10-26
 * @Version: V1.0
 */
@Data
@TableName("tab_update_config")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_update_config对象", description="更新上传配置")
public class TabUpdateConfig implements Serializable {
    private static final long serialVersionUID = 1L;

	/**id*/
	@TableId(type = IdType.AUTO)
    @ApiModelProperty(value = "id")
    private java.lang.Integer id;
	/**配置名称*/
	@Excel(name = "配置名称", width = 15)
    @ApiModelProperty(value = "配置名称")
    private java.lang.String configName;
	/**是否更新前端*/
	@Excel(name = "是否更新前端", width = 15)
    @ApiModelProperty(value = "是否更新前端")
    private java.lang.Integer frontendEnabled;
	/**本地前端部署目录*/
	@Excel(name = "本地前端部署目录", width = 15)
    @ApiModelProperty(value = "本地前端部署目录")
    private java.lang.String frontendDir;
	/**前端散文件目录或ZIP下载地址*/
	@Excel(name = "前端散文件目录或ZIP下载地址", width = 15)
    @ApiModelProperty(value = "前端散文件目录或ZIP下载地址")
    private java.lang.String frontendDownloadUrl;
	/**是否更新后端lib*/
	@Excel(name = "是否更新后端lib", width = 15)
    @ApiModelProperty(value = "是否更新后端lib")
    private java.lang.Integer backendEnabled;
	/**本地lib目录*/
	@Excel(name = "本地lib目录", width = 15)
    @ApiModelProperty(value = "本地lib目录")
    private java.lang.String backendLibDir;
	/**lib散文件目录或ZIP下载地址*/
	@Excel(name = "lib散文件目录或ZIP下载地址", width = 15)
    @ApiModelProperty(value = "lib散文件目录或ZIP下载地址")
    private java.lang.String backendDownloadUrl;
	/**启动JAR路径，不参与更新*/
	@Excel(name = "启动JAR路径，不参与更新", width = 15)
    @ApiModelProperty(value = "启动JAR路径，不参与更新")
    private java.lang.String launcherJarPath;
    /**本机停止脚本绝对路径，Web启用后端更新时必填，兼容已有YML配置*/
    @Excel(name = "停止脚本路径", width = 30)
    @ApiModelProperty(value = "停止脚本绝对路径，启用后端更新时填写完整地址")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String stopScript;
    /**本机启动脚本绝对路径，Web启用后端更新时必填，兼容已有YML配置*/
    @Excel(name = "启动脚本路径", width = 30)
    @ApiModelProperty(value = "启动脚本绝对路径，启用后端更新时填写完整地址")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String startScript;
    /**后端健康检查地址，留空使用YML*/
    @Excel(name = "健康检查地址", width = 30)
    @ApiModelProperty(value = "后端HTTP 200健康检查地址，留空使用app-update.health-url")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String healthUrl;
    @Excel(name = "下载域名白名单", width = 30)
    @ApiModelProperty(value = "下载主机白名单，逗号分隔；留空使用app-update.allowed-hosts")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String allowedHosts;
    @Excel(name = "前端保留目录", width = 30)
    @ApiModelProperty(value = "前端保留相对路径，逗号分隔；留空使用app-update.frontend-preserve-paths")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String frontendPreservePaths;
    @Excel(name = "更新器Java路径", width = 30)
    @ApiModelProperty(value = "Java可执行文件；留空使用app-update.java或当前Java")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private java.lang.String javaExecutable;
	/**是否同步数据库结构*/
	@Excel(name = "是否同步数据库结构", width = 15)
    @ApiModelProperty(value = "是否同步数据库结构")
    private java.lang.Integer dbSyncEnabled;
	/**目标数据库完整结构SQL下载地址，不含数据*/
	@Excel(name = "目标数据库完整结构SQL下载地址，不含数据", width = 15)
    @ApiModelProperty(value = "目标数据库完整结构SQL下载地址，不含数据")
    private java.lang.String dbSchemaUrl;
	/**本地数据源标识，复用应用数据源配置*/
	@Excel(name = "本地数据源标识，复用应用数据源配置", width = 15)
    @ApiModelProperty(value = "本地数据源标识，复用应用数据源配置")
    private java.lang.String dbDatasourceKey;
	/**同步表名前缀，空表示当前库全部业务表*/
	@Excel(name = "同步表名前缀，空表示当前库全部业务表", width = 15)
    @ApiModelProperty(value = "同步表名前缀，空表示当前库全部业务表")
    private java.lang.String dbTablePrefix;
	/**是否允许修改已有字段定义*/
	@Excel(name = "是否允许修改已有字段定义", width = 15)
    @ApiModelProperty(value = "是否允许修改已有字段定义")
    private java.lang.Integer dbAllowModifyColumn;
	/**是否允许删除本地多余字段*/
	@Excel(name = "是否允许删除本地多余字段", width = 15)
    @ApiModelProperty(value = "是否允许删除本地多余字段")
    private java.lang.Integer dbAllowDropColumn;
	/**是否允许删除本地多余表*/
	@Excel(name = "是否允许删除本地多余表", width = 15)
    @ApiModelProperty(value = "是否允许删除本地多余表")
    private java.lang.Integer dbAllowDropTable;
	/**备份目录*/
	@Excel(name = "备份目录", width = 15)
    @ApiModelProperty(value = "备份目录")
    private java.lang.String backupDir;
	/**下载解压临时目录*/
	@Excel(name = "下载解压临时目录", width = 15)
    @ApiModelProperty(value = "下载解压临时目录")
    private java.lang.String downloadDir;
	/**本地当前版本*/
	@Excel(name = "本地当前版本", width = 15)
    @ApiModelProperty(value = "本地当前版本")
    private java.lang.String currentVersion;
	/**版本清单地址，提供目标版本及各下载文件SHA256*/
	@Excel(name = "版本清单地址，提供目标版本及各下载文件SHA256", width = 15)
    @ApiModelProperty(value = "版本清单地址，提供目标版本及各下载文件SHA256")
    private java.lang.String releaseManifestUrl;
	/**IDLE空闲/RUNNING更新中/SUCCESS成功/FAILED失败*/
	@Excel(name = "IDLE空闲/RUNNING更新中/SUCCESS成功/FAILED失败", width = 15)
    @ApiModelProperty(value = "IDLE空闲/RUNNING更新中/SUCCESS成功/FAILED失败")
    private java.lang.String updateStatus;
	/**最近一次更新结束时间*/
	@Excel(name = "最近一次更新结束时间", width = 15, format = "yyyy-MM-dd HH:mm:ss")
    @com.alibaba.fastjson.annotation.JSONField(format = "yyyy-MM-dd HH:mm:ss")
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "最近一次更新结束时间")
    private java.util.Date lastUpdateTime;
	/**最近一次更新结果或错误信息*/
	@Excel(name = "最近一次更新结果或错误信息", width = 15)
    @ApiModelProperty(value = "最近一次更新结果或错误信息")
    private java.lang.String lastUpdateMessage;
	/**enabled*/
	@Excel(name = "enabled", width = 15)
    @ApiModelProperty(value = "enabled")
    private java.lang.Integer enabled;
	/**remark*/
	@Excel(name = "remark", width = 15)
    @ApiModelProperty(value = "remark")
    private java.lang.String remark;
	/**createTime*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "createTime")
    private java.util.Date createTime;
	/**updateTime*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "updateTime")
    private java.util.Date updateTime;
}
