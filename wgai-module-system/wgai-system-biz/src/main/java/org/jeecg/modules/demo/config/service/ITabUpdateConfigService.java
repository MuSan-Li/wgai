package org.jeecg.modules.demo.config.service;

import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @Description: 更新上传配置
 * @Author: jeecg-boot
 * @Date:   2026-10-26
 * @Version: V1.0
 */
public interface ITabUpdateConfigService extends IService<TabUpdateConfig> {
    java.util.Map<String, Object> preview(String id) throws Exception;
    java.util.Map<String, Object> syncUpdate(String id) throws Exception;
    java.util.Map<String,Object> retryUpdate(String id) throws Exception;
    java.util.Map<String,Object> fileChanges(String id,int page,int size,String action) throws Exception;
    java.util.Map<String,Object> defaults();
    java.util.Map<String,Object> progress(String id) throws Exception;
    java.util.Map<String,Object> cleanup(String id) throws Exception;
    java.util.Map<String,Object> checkConfiguration(TabUpdateConfig config) throws Exception;
    void prepareConfiguration(TabUpdateConfig config) throws Exception;
    void refreshResults();
    void assertMutable(java.util.Collection<String> ids);

}
