-- Legacy registration only: update-config endpoints now require login without action permissions. Not required for deployment.
-- Register the sync action under the generated update-config menu. Assign it to the intended deployment administrator role after review.
INSERT INTO sys_permission(id,parent_id,name,menu_type,perms,perms_type,is_route,is_leaf,always_show,keep_alive,hidden,hide_tab,status,del_flag,rule_flag,internal_or_external,create_by,create_time)
SELECT '2026102602179740107','2026102602179730100','同步更新',2,'org.jeecg.modules.demo:tab_update_config:syncUpdate','1',0,1,0,0,0,0,'1',0,0,0,'admin',CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE perms='org.jeecg.modules.demo:tab_update_config:syncUpdate' AND del_flag=0);
