-- 注意：该页面对应的前台目录为views/config文件夹下
-- 如果你想更改到其他目录，请修改sql中component字段对应的值


INSERT INTO sys_permission(id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, status, del_flag, rule_flag, create_by, create_time, update_by, update_time, internal_or_external) 
VALUES ('2026102602179730100', NULL, '更新上传配置', '/config/tabUpdateConfigList', 'config/TabUpdateConfigList', NULL, NULL, 0, NULL, '1', 0.00, 0, NULL, 1, 1, 0, 0, 0, NULL, '1', 0, 0, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0);

-- 权限控制sql
-- 新增
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740101', '2026102602179730100', '添加更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:add', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);
-- 编辑
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740102', '2026102602179730100', '编辑更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:edit', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);
-- 删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740103', '2026102602179730100', '删除更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:delete', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);
-- 批量删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740104', '2026102602179730100', '批量删除更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:deleteBatch', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);
-- 导出excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740105', '2026102602179730100', '导出excel_更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:exportXls', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);
-- 导入excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2026102602179740106', '2026102602179730100', '导入excel_更新上传配置', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_update_config:importExcel', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-10-26 14:17:10', NULL, NULL, 0, 0, '1', 0);