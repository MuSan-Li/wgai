package org.jeecg.modules.demo.config.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import org.jeecg.modules.demo.config.service.ITabUpdateConfigService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import com.alibaba.fastjson.JSON;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jeecg.common.aspect.annotation.AutoLog;

 /**
 * @Description: 更新上传配置
 * @Author: jeecg-boot
 * @Date:   2026-10-26
 * @Version: V1.0
 */
@Api(tags="更新上传配置")
// 平台更新操作统一沿用全局登录鉴权，不要求单独的按钮权限。
@RestController
@RequestMapping("/config/tabUpdateConfig")
@Slf4j
public class TabUpdateConfigController extends JeecgController<TabUpdateConfig, ITabUpdateConfigService> {
	@Autowired
	private ITabUpdateConfigService tabUpdateConfigService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabUpdateConfig
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "更新上传配置-分页列表查询")
	@ApiOperation(value="更新上传配置-分页列表查询", notes="更新上传配置-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabUpdateConfig>> queryPageList(TabUpdateConfig tabUpdateConfig,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		tabUpdateConfigService.refreshResults();
		QueryWrapper<TabUpdateConfig> queryWrapper = QueryGenerator.initQueryWrapper(tabUpdateConfig, req.getParameterMap());
		Page<TabUpdateConfig> page = new Page<TabUpdateConfig>(pageNo, pageSize);
		IPage<TabUpdateConfig> pageList = tabUpdateConfigService.page(page, queryWrapper);
		return Result.OK(pageList);
	}
	
	/**
	 *   添加
	 *
	 * @param tabUpdateConfig
	 * @return
	 */
	@AutoLog(value = "更新上传配置-添加")
	@ApiOperation(value="更新上传配置-添加", notes="更新上传配置-添加")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabUpdateConfig tabUpdateConfig) {
		tabUpdateConfig.setId(null);
		tabUpdateConfig.setUpdateStatus("IDLE");
		tabUpdateConfig.setLastUpdateMessage(null);
		tabUpdateConfig.setLastUpdateTime(null);
		try { tabUpdateConfigService.prepareConfiguration(tabUpdateConfig); }
        catch(Exception ex) { return Result.error(ex.getMessage()); }
        tabUpdateConfigService.save(tabUpdateConfig);
		return Result.OK("添加成功！");
	}
	
	/**
	 *  编辑
	 *
	 * @param tabUpdateConfig
	 * @return
	 */
	@AutoLog(value = "更新上传配置-编辑")
	@ApiOperation(value="更新上传配置-编辑", notes="更新上传配置-编辑")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabUpdateConfig tabUpdateConfig) {
		tabUpdateConfigService.assertMutable(Arrays.asList(String.valueOf(tabUpdateConfig.getId())));
		TabUpdateConfig saved = tabUpdateConfigService.getById(tabUpdateConfig.getId());
		tabUpdateConfig.setUpdateStatus(saved.getUpdateStatus());
		if (saved.getCurrentVersion() != null && !saved.getCurrentVersion().trim().isEmpty()) tabUpdateConfig.setCurrentVersion(saved.getCurrentVersion());
		tabUpdateConfig.setLastUpdateTime(saved.getLastUpdateTime());
		tabUpdateConfig.setLastUpdateMessage(saved.getLastUpdateMessage());
		// Merge partial edits so validation always checks the complete saved deployment configuration.
        org.springframework.beans.BeanWrapper incoming=new org.springframework.beans.BeanWrapperImpl(tabUpdateConfig);
        java.util.List<String> absent=new java.util.ArrayList<>();
        for(java.beans.PropertyDescriptor property:incoming.getPropertyDescriptors())
            if(incoming.isReadableProperty(property.getName()) && incoming.getPropertyValue(property.getName())==null)absent.add(property.getName());
        org.springframework.beans.BeanUtils.copyProperties(tabUpdateConfig,saved,absent.toArray(new String[0]));
        try { tabUpdateConfigService.prepareConfiguration(saved); }
        catch(Exception ex) { return Result.error(ex.getMessage()); }
        tabUpdateConfigService.updateById(saved);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "更新上传配置-通过id删除")
	@ApiOperation(value="更新上传配置-通过id删除", notes="更新上传配置-通过id删除")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabUpdateConfigService.assertMutable(Arrays.asList(id));
		tabUpdateConfigService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "更新上传配置-批量删除")
	@ApiOperation(value="更新上传配置-批量删除", notes="更新上传配置-批量删除")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		tabUpdateConfigService.assertMutable(Arrays.asList(ids.split(",")));
		this.tabUpdateConfigService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "更新上传配置-通过id查询")
	@ApiOperation(value="更新上传配置-通过id查询", notes="更新上传配置-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabUpdateConfig> queryById(@RequestParam(name="id",required=true) String id) {
		tabUpdateConfigService.refreshResults();
		TabUpdateConfig tabUpdateConfig = tabUpdateConfigService.getById(id);
		if(tabUpdateConfig==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabUpdateConfig);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabUpdateConfig
    */
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabUpdateConfig tabUpdateConfig) {
        return super.exportXls(request, tabUpdateConfig, TabUpdateConfig.class, "更新上传配置");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabUpdateConfig.class);
    }

    @ApiOperation(value = "同步更新配置校验")
    @GetMapping("/syncPreview")
    public Result<?> syncPreview(@RequestParam String id) {
        try { return Result.OK(tabUpdateConfigService.preview(id)); }
        catch (Exception ex) { return Result.error(ex.getMessage()); }
    }

    @AutoLog(value = "更新配置-同步更新")
    @ApiOperation(value = "提交同步更新任务")
    @PostMapping("/syncUpdate")
    public Result<?> syncUpdate(@RequestBody Map<String, String> request) {
        try {
            String id = request.get("id");
            if (id == null || id.trim().isEmpty()) return Result.error("请选择更新配置");
            return Result.OK(tabUpdateConfigService.syncUpdate(id));
        } catch (Exception ex) { return Result.error(ex.getMessage()); }
    }
    @PostMapping("/retryUpdate")
    public Result<?> retryUpdate(@RequestBody Map<String,String> request){
        try{return Result.OK(tabUpdateConfigService.retryUpdate(request.get("id")));}catch(Exception ex){return Result.error(ex.getMessage());}
    }
    @GetMapping("/fileChanges")
    public Result<?> fileChanges(@RequestParam String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String action){
        try{return Result.OK(tabUpdateConfigService.fileChanges(id,page,size,action));}catch(Exception ex){return Result.error(ex.getMessage());}
    }
    @ApiOperation(value="检查全部配置并创建所填路径的缺失脚本，不启停服务")
    @PostMapping("/validateConfig")
    public Result<?> validateConfig(@RequestBody TabUpdateConfig config) {
        try { return Result.OK(tabUpdateConfigService.checkConfiguration(config)); }
        catch(Exception ex) { return Result.error(ex.getMessage()); }
    }

    @GetMapping("/defaults")
    public Result<?> defaults(){ return Result.OK(tabUpdateConfigService.defaults()); }

    @GetMapping("/progress")
    public Result<?> progress(@RequestParam String id,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        try{return Result.OK(tabUpdateConfigService.progress(id));}
        catch(Exception ex){return Result.error(ex.getMessage());}
    }

    @PostMapping("/cleanup")
    public Result<?> cleanup(@RequestBody Map<String,String> request){
        try{return Result.OK(tabUpdateConfigService.cleanup(request.get("id")));}
        catch(Exception ex){return Result.error(ex.getMessage());}
    }

}
