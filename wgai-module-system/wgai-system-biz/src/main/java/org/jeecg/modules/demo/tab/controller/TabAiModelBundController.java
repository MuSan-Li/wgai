package org.jeecg.modules.demo.tab.controller;

import java.util.Arrays;
import org.jeecg.modules.tab.AIModel.onnx.JavaVideoPushWorker;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.tab.service.ITabAiModelBundService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import com.alibaba.fastjson.JSON;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jeecg.common.aspect.annotation.AutoLog;

 /**
 * @Description: 模型绑定
 * @Author: WGAI
 * @Date:   2024-03-15
 * @Version: V1.0
 */
@Api(tags="模型绑定")
@RestController
@RequestMapping("/tab/tabAiModelBund")
@Slf4j
public class TabAiModelBundController extends JeecgController<TabAiModelBund, ITabAiModelBundService> {
	@Autowired
	private ITabAiModelBundService tabAiModelBundService;

	 public static void main(String[] args) {
		 String  a="0103020000B844";
		 System.out.println(a.length());
	 }






	/**
	 * 分页列表查询
	 *
	 * @param tabAiModelBund
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "模型绑定-分页列表查询")
	@ApiOperation(value="模型绑定-分页列表查询", notes="模型绑定-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabAiModelBund>> queryPageList(TabAiModelBund tabAiModelBund,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {

		QueryWrapper<TabAiModelBund> queryWrapper = QueryGenerator.initQueryWrapper(tabAiModelBund, req.getParameterMap());
		queryWrapper.eq("space_one","0");
		Page<TabAiModelBund> page = new Page<TabAiModelBund>(pageNo, pageSize);
		IPage<TabAiModelBund> pageList = tabAiModelBundService.page(page, queryWrapper);
		return Result.OK(pageList);
	}
	 @ApiOperation(value="模型绑定-分页列表查询", notes="模型绑定-分页列表查询")
	 @GetMapping(value = "/listVideo")
	 public Result<IPage<TabAiModelBund>> queryPageListVideo(TabAiModelBund tabAiModelBund,
														@RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
														@RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
														HttpServletRequest req) {

		 QueryWrapper<TabAiModelBund> queryWrapper = QueryGenerator.initQueryWrapper(tabAiModelBund, req.getParameterMap());
		 queryWrapper.ne("space_one","0");
		 Page<TabAiModelBund> page = new Page<TabAiModelBund>(pageNo, pageSize);
		 IPage<TabAiModelBund> pageList = tabAiModelBundService.page(page, queryWrapper);
		 return Result.OK(pageList);
	 }

	 /**
	  * 树形查询
	  *
	  * @param tabAiModelBund
	  * @param req
	  * @return
	  */
	 //@AutoLog(value = "模型绑定-分页列表查询")
	 @ApiOperation(value="模型绑定-树形查询", notes="模型绑定-树形查询")
	 @GetMapping(value = "/Treelist")
	 public Result<List<TabAiModelBund>> Treelist(TabAiModelBund tabAiModelBund,  HttpServletRequest req) {

		 LambdaQueryWrapper<TabAiModelBund> queryWrapper=new LambdaQueryWrapper<>();
		 if(StringUtils.isNotEmpty(tabAiModelBund.getModelName())){
			 System.out.println(tabAiModelBund.getModelName());
			 queryWrapper.like(TabAiModelBund::getSpaceTwo,tabAiModelBund.getModelName());
		 }
		 queryWrapper.eq(TabAiModelBund::getSpaceOne,"1");
		 return Result.OK(tabAiModelBundService.list(queryWrapper));
	 }

    @GetMapping("/pushStatus")
    public Result<com.alibaba.fastjson.JSONObject> pushStatus(@RequestParam String id) {
        return Result.OK(JavaVideoPushWorker.status(id));
    }

    /**共享已保存的视频脚本引用，不读取或执行脚本内容。*/
    @ApiOperation(value = "可复用的 Python 视频脚本")
    @GetMapping("/pythonScripts")
    public Result<List<String>> pythonScripts() {
        QueryWrapper<TabAiModelBund> query = new QueryWrapper<>();
        query.select("DISTINCT python_script_path")
             .isNotNull("python_script_path").ne("python_script_path", "")
             .orderByAsc("python_script_path");
        List<String> scripts = tabAiModelBundService.list(query).stream()
            .map(TabAiModelBund::getPythonScriptPath).collect(Collectors.toList());
        return Result.OK(scripts);
    }

    // 编辑接口也供其他页面局部更新；未传字段按已保存值校验，不覆盖旧配置。
    private String validatePushConfig(TabAiModelBund incoming, TabAiModelBund existing) {
        TabAiModelBund old = existing == null ? new TabAiModelBund() : existing;
        String mode = configValue(incoming.getRecognitionMode(), old.getRecognitionMode());
        if (!"2".equals(mode)) return null;
        Integer decode = incoming.getGpuDecode() == null ? old.getGpuDecode() : incoming.getGpuDecode();
        if (decode != null && decode != 0 && decode != 1) return "GPU 解码配置只能为0或1";
        String engine = configValue(incoming.getExecutionEngine(), old.getExecutionEngine());
        if (engine == null) engine = "java";
        if (!"java".equals(engine) && !"python".equals(engine)) return "执行方式只能为java或python";
        String type = configValue(incoming.getSpaceOne(), old.getSpaceOne());
        if ("0".equals(type)) return "图片识别不支持视频流推送重组";
        String input = configValue(incoming.getInferenceVideoUrl(), old.getInferenceVideoUrl());
        String output = configValue(incoming.getZlmPushUrl(), old.getZlmPushUrl());
        String script = configValue(incoming.getPythonScriptPath(), old.getPythonScriptPath());
        Integer fps = incoming.getInferenceFps() == null ? old.getInferenceFps() : incoming.getInferenceFps();
        if (!validStreamAddress(input)) return "请填写有效的推理视频流地址";
        if (!validStreamAddress(output) || !output.matches("(?i)^rtmps?://.+|^rtsp://.+")) return "ZLM 推流地址须使用 RTMP(S) 或 RTSP";
        if (fps == null || fps < 1 || fps > 60) return "识别频率须为 1至60 的整数";
        if ("python".equals(engine) && (StringUtils.isBlank(script) || script.length() > 1024 || !script.toLowerCase(java.util.Locale.ROOT).endsWith(".py"))) {
            return "请选择或上传 Python 脚本（.py）";
        }
        return null;
    }

    private String configValue(String incoming, String existing) {
        return incoming == null ? existing : incoming;
    }

    private boolean validStreamAddress(String value) {
        return StringUtils.isNotBlank(value) && value.length() <= 1024
            && value.matches("(?i)^(rtsp|rtsps|rtmp|rtmps|http|https|ws|wss)://[^\\s/]+(?:/[^\\s]*)?$");
    }

	 @Value(value = "${jeecg.path.upload}")
	 private String uploadpath;
	/**
	 *   添加
	 *
	 * @param tabAiModelBund
	 * @return
	 */
	@AutoLog(value = "模型绑定-添加")
	@ApiOperation(value="模型绑定-添加", notes="模型绑定-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_model_bund:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabAiModelBund tabAiModelBund) {
        String configError = validatePushConfig(tabAiModelBund, null);
        if (configError != null) return Result.error(configError);
		if("0".equals(tabAiModelBund.getSpaceOne())){
			if(StringUtils.isEmpty(tabAiModelBund.getSaveUrl())&&StringUtils.isNotEmpty(tabAiModelBund.getSendUrl())){
				AIModelYolo3 modelYolo3=new AIModelYolo3();
				String saveurl=modelYolo3.SavePicInLocalhost(tabAiModelBund.getSendUrl(),uploadpath);
				log.info("图片下载保存地址{}",saveurl);
				tabAiModelBund.setSaveUrl(saveurl);
			}
		}
		tabAiModelBundService.save(tabAiModelBund);
		return Result.OK(tabAiModelBund.getId());
	}
	
	/**
	 *  编辑
	 *
	 * @param tabAiModelBund
	 * @return
	 */
	@AutoLog(value = "模型绑定-编辑")
	@ApiOperation(value="模型绑定-编辑", notes="模型绑定-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_model_bund:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabAiModelBund tabAiModelBund) {
        TabAiModelBund existing = tabAiModelBundService.getById(tabAiModelBund.getId());
        if (existing == null) return Result.error("未找到对应数据");
        String configError = validatePushConfig(tabAiModelBund, existing);
        if (configError != null) return Result.error(configError);
		tabAiModelBundService.updateById(tabAiModelBund);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "模型绑定-通过id删除")
	@ApiOperation(value="模型绑定-通过id删除", notes="模型绑定-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_model_bund:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabAiModelBundService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "模型绑定-批量删除")
	@ApiOperation(value="模型绑定-批量删除", notes="模型绑定-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_model_bund:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabAiModelBundService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "模型绑定-通过id查询")
	@ApiOperation(value="模型绑定-通过id查询", notes="模型绑定-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabAiModelBund> queryById(@RequestParam(name="id",required=true) String id) {
		TabAiModelBund tabAiModelBund = tabAiModelBundService.getById(id);
		if(tabAiModelBund==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabAiModelBund);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabAiModelBund
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_model_bund:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabAiModelBund tabAiModelBund) {
        return super.exportXls(request, tabAiModelBund, TabAiModelBund.class, "模型绑定");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_ai_model_bund:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabAiModelBund.class);
    }

}
