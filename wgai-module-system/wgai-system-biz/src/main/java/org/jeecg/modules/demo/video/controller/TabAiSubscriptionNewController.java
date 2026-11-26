package org.jeecg.modules.demo.video.controller;

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
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.service.ITabAiSubscriptionNewService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
 * @Description: 多程第三方订阅
 * @Author: wggg
 * @Date:   2025-05-20
 * @Version: V1.0
 */
@Api(tags="多程第三方订阅")
@RestController
@RequestMapping("/video/tabAiSubscriptionNew")
@Slf4j
public class TabAiSubscriptionNewController extends JeecgController<TabAiSubscriptionNew, ITabAiSubscriptionNewService> {
	@Autowired
	private ITabAiSubscriptionNewService tabAiSubscriptionNewService;
    @Autowired private org.jeecg.modules.demo.video.service.impl.SubscriptionHeartbeatService heartbeatService;
    @org.springframework.beans.factory.annotation.Value("${ai.subscription.runtime-stale-seconds:60}") private long runtimeStaleSeconds = 60;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabAiSubscriptionNew
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "多程第三方订阅-分页列表查询")
	@ApiOperation(value="多程第三方订阅-分页列表查询", notes="多程第三方订阅-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabAiSubscriptionNew>> queryPageList(TabAiSubscriptionNew tabAiSubscriptionNew,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		QueryWrapper<TabAiSubscriptionNew> queryWrapper = QueryGenerator.initQueryWrapper(tabAiSubscriptionNew, req.getParameterMap());
		Page<TabAiSubscriptionNew> page = new Page<TabAiSubscriptionNew>(pageNo, pageSize);
		IPage<TabAiSubscriptionNew> pageList = tabAiSubscriptionNewService.page(page, queryWrapper);
		for (TabAiSubscriptionNew item : pageList.getRecords()) item.setRuntimeInfo(org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus.snapshot(item, Math.max(5, runtimeStaleSeconds)));
		return Result.OK(pageList);
	}
	
    /** Only read the visible subscriptions' status; no dictionary/page refresh on every poll. */
    @ApiOperation(value = "订阅实时推理状态", notes = "按当前页ID返回本机实际推理进展，与启停标记分开")
    @GetMapping("/runtimeStatus")
    public Result<Map<String, Map<String, Object>>> runtimeStatus(
            @RequestParam(name = "ids") String ids, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        List<String> requested = Arrays.stream(ids.split(",")).map(String::trim)
                .filter(id -> !id.isEmpty()).distinct().collect(Collectors.toList());
        if (requested.size() > 200) return Result.error("每次最多查询200条订阅的实时状态");
        Map<String, Map<String, Object>> result = new java.util.LinkedHashMap<>();
        if (requested.isEmpty()) return Result.OK(result);
        QueryWrapper<TabAiSubscriptionNew> query = QueryGenerator.initQueryWrapper(
                new TabAiSubscriptionNew(), java.util.Collections.emptyMap());
        query.select("id", "run_state", "dify_start_end", "dify_start_time").in("id", requested);
        for (TabAiSubscriptionNew item : tabAiSubscriptionNewService.list(query)) {
            Map<String, Object> info = org.jeecg.modules.demo.video.util.SubscriptionRuntimeStatus
                    .snapshot(item, Math.max(5, runtimeStaleSeconds));
            info.put("runState", item.getRunState());
            result.put(item.getId(), info);
        }
        return Result.OK(result);
    }
/**
	 *   添加
	 *
	 * @param tabAiSubscriptionNew
	 * @return
	 */
	@AutoLog(value = "多程第三方订阅-添加")
	@ApiOperation(value="多程第三方订阅-添加", notes="多程第三方订阅-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:add")
	@PostMapping(value = "/add")
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<String> add(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew,
                              @RequestParam(name = "syncHeartbeatToOthers", defaultValue = "false") boolean syncHeartbeatToOthers) {
		org.jeecg.modules.demo.video.util.InferenceOptions.validate(tabAiSubscriptionNew);
		tabAiSubscriptionNew.setRunState(0);
        if (!tabAiSubscriptionNewService.save(tabAiSubscriptionNew)) throw new IllegalStateException("订阅保存失败");
        int synced = syncHeartbeatToOthers ? tabAiSubscriptionNewService.syncHeartbeatToOthers(tabAiSubscriptionNew) : 0;
        return Result.OK(syncHeartbeatToOthers ? "添加成功，已同步保活配置到其他条目（处理" + synced + "条）" : "添加成功！");
	}
	
	/**
	 *  编辑
	 *
	 * @param tabAiSubscriptionNew
	 * @return
	 */
	@AutoLog(value = "多程第三方订阅-编辑")
	@ApiOperation(value="多程第三方订阅-编辑", notes="多程第三方订阅-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	@org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<String> edit(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew,
                               @RequestParam(name = "syncHeartbeatToOthers", defaultValue = "false") boolean syncHeartbeatToOthers) {
		org.jeecg.modules.demo.video.util.InferenceOptions.validate(tabAiSubscriptionNew);
		if (tabAiSubscriptionNew.getId() == null || tabAiSubscriptionNew.getId().trim().isEmpty()) {
			return Result.error("缺少订阅ID");
		}
        TabAiSubscriptionNew existing = tabAiSubscriptionNewService.getById(tabAiSubscriptionNew.getId());
        if (existing == null) return Result.error("订阅不存在");
        if (Integer.valueOf(1).equals(existing.getRunState())) return Result.error("请先停止订阅再修改配置");
		// 编辑表单清空时间时，显式写入 NULL，避免默认更新策略保留旧时间。
		LambdaUpdateWrapper<TabAiSubscriptionNew> update = new LambdaUpdateWrapper<>();
		update.eq(TabAiSubscriptionNew::getId, tabAiSubscriptionNew.getId())
			.set(tabAiSubscriptionNew.getDifyStartEnd() == null, TabAiSubscriptionNew::getDifyStartEnd, null)
			.set(tabAiSubscriptionNew.getDifyStartTime() == null, TabAiSubscriptionNew::getDifyStartTime, null);
        if (!tabAiSubscriptionNewService.update(tabAiSubscriptionNew, update)) throw new IllegalStateException("订阅保存失败");
        int synced = syncHeartbeatToOthers ? tabAiSubscriptionNewService.syncHeartbeatToOthers(tabAiSubscriptionNew) : 0;
        return Result.OK(syncHeartbeatToOthers ? "保存成功，已同步保活配置到其他条目（处理" + synced + "条）" : "编辑成功!");
	}



	 /**
	  *  编辑
	  *
	  * @param tabAiSubscriptionNew
	  * @return
	  */
	 @AutoLog(value = "多程第三方订阅-开始执行")
	 @ApiOperation(value="多程第三方订阅-开始执行", notes="多程第三方订阅-开始执行")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/startBatch", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> startBatch(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew) {

		 try {
			 String ids=tabAiSubscriptionNew.getId();
			 List<TabAiSubscriptionNew> tabAiSubscriptionNews=tabAiSubscriptionNewService.listByIds(Arrays.asList(ids.split(",")));
            for (TabAiSubscriptionNew tab : tabAiSubscriptionNews) {
                if (Integer.valueOf(1).equals(tabAiSubscriptionNew.getRunState())) {
                    tabAiSubscriptionNewService.startAi(tab);
                } else {
                    tabAiSubscriptionNewService.stopAi(tab);
                }
            }


		 } catch (IOException e) {
			 throw new RuntimeException(e);
		 }

		 return Result.OK("批量操作成功!");
	 }

	 /**
	  *  编辑
	  *
	  * @param tabAiSubscriptionNew
	  * @return
	  */
	 @AutoLog(value = "多程第三方订阅-开始执行")
	 @ApiOperation(value="多程第三方订阅-开始执行", notes="多程第三方订阅-开始执行")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/start", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> start(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew) {

		 try {
			 log.info("[开始进程 单个执行]");
			 tabAiSubscriptionNewService.startAi(tabAiSubscriptionNew);
		 } catch (IOException e) {
			 throw new RuntimeException(e);
		 }

		 return Result.OK("开始执行!");
	 }


	 @AutoLog(value = "多程第三方订阅-结束执行")
	 @ApiOperation(value="多程第三方订阅-开始执行", notes="多程第三方订阅-开始执行")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/stop", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> stop(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew) {

		 tabAiSubscriptionNewService.stopAi(tabAiSubscriptionNew);
		 return Result.OK("结束执行!");
	 }

	 @AutoLog(value = "设置边界")
	 @ApiOperation(value="设置边界", notes="设置边界")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/setBox", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> setBox(@RequestBody TabAiSubscriptionNew tabAiSubscriptionNew) {

		 tabAiSubscriptionNewService.setBox(tabAiSubscriptionNew);
		 return Result.OK("结束执行!");
	 }

	 @AutoLog(value = "获取视频原图")
	 @ApiOperation(value="获取视频原图", notes="获取视频原图")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/getVideoPic", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<String> getVideoPic(@RequestParam(name="id",required=true) String id) {


		 return tabAiSubscriptionNewService.getVideoPic(id);
	 }
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "多程第三方订阅-通过id删除")
	@ApiOperation(value="多程第三方订阅-通过id删除", notes="多程第三方订阅-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabAiSubscriptionNewService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "多程第三方订阅-批量删除")
	@ApiOperation(value="多程第三方订阅-批量删除", notes="多程第三方订阅-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabAiSubscriptionNewService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "多程第三方订阅-通过id查询")
	@ApiOperation(value="多程第三方订阅-通过id查询", notes="多程第三方订阅-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabAiSubscriptionNew> queryById(@RequestParam(name="id",required=true) String id) {
		TabAiSubscriptionNew tabAiSubscriptionNew = tabAiSubscriptionNewService.getById(id);
		if(tabAiSubscriptionNew==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabAiSubscriptionNew);
	}


	 @ApiOperation(value="多程第三方订阅-通过id查询", notes="多程第三方订阅-通过id查询")
	 @GetMapping(value = "/test")
	 public Result<?> test(@RequestParam(name="id",required=true) String id) {




		 return tabAiSubscriptionNewService.test(id);
	 }

    /**
    * 导出excel
    *
    * @param request
    * @param tabAiSubscriptionNew
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabAiSubscriptionNew tabAiSubscriptionNew) {
        return super.exportXls(request, tabAiSubscriptionNew, TabAiSubscriptionNew.class, "多程第三方订阅");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_ai_subscription_new:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabAiSubscriptionNew.class);
    }

    @ApiOperation(value = "保活消息测试上传", notes = "使用当前表单配置，只发送一次，不保存配置或启动推理")
    @PostMapping("/testHeartbeat")
    public Result<?> testHeartbeat(@RequestBody TabAiSubscriptionNew config) {
        java.util.concurrent.CompletableFuture<com.alibaba.fastjson.JSONObject> task = null;
        try {
            task = heartbeatService.testUpload(config);
            // Keep the HTTP response in the original Shiro request. DeferredResult would
            // cause ASYNC redispatch without this application's thread-bound SecurityManager.
            // Only this manual test request waits; upload still runs in the dedicated pool.
            return Result.OK(task.get(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (java.util.concurrent.TimeoutException ex) {
            if (task != null) task.cancel(false);
            return Result.error("测试上传等待超时，已发送的请求结果可能尚未确认，请通过保活上传日志核对");
        } catch (InterruptedException ex) {
            if (task != null) task.cancel(false);
            Thread.currentThread().interrupt();
            return Result.error("测试上传等待已中断，请通过保活上传日志核对结果");
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            return Result.error(cause == null ? "测试上传失败" : cause.getMessage());
        } catch (Exception ex) { return Result.error(ex.getMessage()); }
    }

}
