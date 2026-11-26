package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.fastjson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Add missing seed rows only; downloaded SQL is parsed, never executed directly. */
public final class UpdateSeedData {
    private UpdateSeedData() { }
    public static final class Payload {
        final List<Row> rows = new ArrayList<>();
        final JSONObject rules;
        Payload(JSONObject rules) { this.rules = rules == null ? new JSONObject() : rules; }
    }
    private static final class Row {
        String table;
        LinkedHashMap<String,Object> values = new LinkedHashMap<>();
    }
    private static final class Table {
        List<String> primary = new ArrayList<>();
        Set<String> columns = new HashSet<>();
        Map<String,String> references = new LinkedHashMap<>();
        List<String> keys;
    }
    private static String name(String value) {
        String result = value.replace(String.valueOf((char)96), "");
        if (!result.matches("[a-zA-Z0-9_$]+")) throw new IllegalArgumentException("不支持跨库或特殊名称: " + value);
        if (result.equalsIgnoreCase("tab_update_config")) throw new IllegalArgumentException("基础数据不能修改更新配置表");
        return result;
    }
    private static String column(String value) { return name(value).toLowerCase(Locale.ROOT); }
    private static String q(String value) { return String.valueOf((char)96) + name(value) + (char)96; }
    private static Object literal(SQLExpr value) throws IOException {
        if (value instanceof SQLCharExpr) return ((SQLCharExpr)value).getText();
        if (value instanceof SQLIntegerExpr) return ((SQLIntegerExpr)value).getNumber();
        if (value instanceof SQLNumberExpr) return ((SQLNumberExpr)value).getNumber();
        if (value instanceof SQLNullExpr) return null;
        if (value instanceof SQLBooleanExpr) return ((SQLBooleanExpr)value).getValue() ? 1 : 0;
        throw new IOException("基础数据只允许字符串、数值和NULL，禁止表达式/函数: " + value);
    }
    public static Payload parse(String sql, JSONObject rules) throws IOException {
        Payload payload = new Payload(rules);
        for (String table : payload.rules.keySet()) {
            name(table);
            JSONObject rule = payload.rules.getJSONObject(table);
            if (rule == null) throw new IOException("基础数据规则格式错误: " + table);
            if (rule.containsKey("keys")) for (Object column : rule.getJSONArray("keys")) name(String.valueOf(column));
            if (rule.containsKey("references")) for (Map.Entry<String,Object> ref : rule.getJSONObject("references").entrySet()) {
                name(ref.getKey()); name(String.valueOf(ref.getValue()));
            }
        }
        for (SQLStatement statement : SQLUtils.parseStatements(sql, "mysql")) {
            String type = statement.getClass().getSimpleName();
            if (type.equals("SQLSetStatement") || type.equals("SQLUseStatement")) continue;
            if (!(statement instanceof SQLInsertStatement)) throw new IOException("基础数据文件只允许INSERT VALUES，不允许: " + type);
            SQLInsertStatement insert = (SQLInsertStatement)statement;
            if (insert.getQuery() != null || insert.getColumns().isEmpty()) throw new IOException("INSERT必须包含完整列名和VALUES；导出时勾选包含字段名");
            if (insert instanceof MySqlInsertStatement) {
                MySqlInsertStatement mysql = (MySqlInsertStatement)insert;
                if (mysql.isIgnore() || !mysql.getDuplicateKeyUpdate().isEmpty()) throw new IOException("禁止IGNORE或ON DUPLICATE KEY；导入程序负责跳过已有数据");
            }
            String table = name(insert.getTableName().toString());
            for (SQLInsertStatement.ValuesClause clause : insert.getValuesList()) {
                if (payload.rows.size() >= 20000) throw new IOException("单次基础数据最多20000条");
                if (clause.getValues().size() != insert.getColumns().size()) throw new IOException("INSERT列和值数量不一致");
                Row row = new Row(); row.table = table;
                for (int i=0;i<insert.getColumns().size();i++) {
                    String column = column(insert.getColumns().get(i).toString());
                    if (row.values.containsKey(column)) throw new IOException("重复列: " + column);
                    row.values.put(column, literal(clause.getValues().get(i)));
                }
                payload.rows.add(row);
            }
        }
        if (payload.rows.isEmpty()) throw new IOException("基础数据文件没有INSERT记录");
        return payload;
    }
    private static Table metadata(Connection conn,String table,JSONObject rule) throws SQLException,IOException {
        Table result = new Table();
        try (ResultSet rs=conn.getMetaData().getColumns(conn.getCatalog(),null,table,null)) {
            while(rs.next()) result.columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        TreeMap<Integer,String> primary = new TreeMap<>();
        try (ResultSet rs=conn.getMetaData().getPrimaryKeys(conn.getCatalog(),null,table)) {
            while(rs.next()) primary.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        result.primary.addAll(primary.values());
        if(result.columns.isEmpty() || result.primary.isEmpty()) throw new IOException("基础数据表不存在或没有主键: "+table);
        if(conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")){
            try(PreparedStatement ps=conn.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
                ps.setString(1,conn.getCatalog());ps.setString(2,table);
                try(ResultSet rs=ps.executeQuery()){
                    if(!rs.next() || !"InnoDB".equalsIgnoreCase(rs.getString(1)))throw new IOException("基础数据事务同步要求InnoDB表: "+table);
                }
            }
        }
        if(table.equals("sys_dict_item")) result.references.put("dict_id","sys_dict");
        if(table.equals("sys_permission")) result.references.put("parent_id","sys_permission");
        try (ResultSet rs=conn.getMetaData().getImportedKeys(conn.getCatalog(),null,table)) {
            Map<String,Integer> counts=new HashMap<>();
            while(rs.next()) {
                String fk=Objects.toString(rs.getString("FK_NAME"),"");
                if(counts.merge(fk,1,Integer::sum)>1)throw new IOException("复合外键请单独迁移: "+table);
                String parent=name(rs.getString("PKTABLE_NAME")), parentColumn=rs.getString("PKCOLUMN_NAME");
                List<String> parentKeys=new ArrayList<>();
                try(ResultSet pk=conn.getMetaData().getPrimaryKeys(conn.getCatalog(),null,parent)){
                    while(pk.next())parentKeys.add(pk.getString("COLUMN_NAME"));
                }
                if(parentKeys.size()!=1 || !parentKeys.get(0).equalsIgnoreCase(parentColumn))throw new IOException("只支持指向父表单列主键的外键: "+table);
                result.references.put(column(rs.getString("FKCOLUMN_NAME")),parent);
            }
        }
        if(rule != null) {
            if(rule.containsKey("keys")) {
                result.keys=new ArrayList<>();
                for(Object key:rule.getJSONArray("keys")) result.keys.add(column(String.valueOf(key)));
                if(result.keys.isEmpty())throw new IOException("匹配字段不能为空: "+table);
            }
            JSONObject references=rule.getJSONObject("references");
            if(references!=null) for(String col:references.keySet()) result.references.put(column(col),name(references.getString(col)));
        }
        if(result.keys!=null && !result.columns.containsAll(result.keys) || !result.columns.containsAll(result.references.keySet())) throw new IOException("基础数据规则字段不存在: "+table);
        return result;
    }
    private static boolean root(Object value) { return value==null || value.toString().isEmpty() || value.toString().equals("0"); }
    private static Object identity(Row row,Table table) throws IOException {
        List<Object> keys=new ArrayList<>();
        for(String col:table.primary) {
            Object value=row.values.get(col);
            if(value==null)throw new IOException("基础数据必须明确提供主键: "+row.table+"."+col);
            keys.add(value.toString());
        }
        return keys.size()==1 ? keys.get(0) : JSON.toJSONString(keys);
    }
    private static List<String> keys(Row row,Table table) {
        if(table.keys!=null)return table.keys;
        if(row.table.equals("sys_dict"))return Arrays.asList("dict_code");
        if(row.table.equals("sys_dict_item"))return Arrays.asList("dict_id","item_value");
        if(row.table.equals("sys_permission")) {
            if(!root(row.values.get("perms")))return Arrays.asList("menu_type","parent_id","perms");
            if(!root(row.values.get("url")))return Arrays.asList("menu_type","parent_id","url");
            return Arrays.asList("menu_type","parent_id","name");
        }
        return table.primary;
    }
    private static Map<String,Object> lookup(Connection conn,String table,List<String> keys,Map<String,Object> values) throws SQLException,IOException {
        StringBuilder where=new StringBuilder();
        List<Object> params=new ArrayList<>();
        for(String key:keys) {
            if(!values.containsKey(key))throw new IOException("基础数据缺少匹配字段: "+table+"."+key);
            if(where.length()>0)where.append(" AND ");
            Object value=values.get(key);
            if(table.equals("sys_permission") && key.equals("parent_id") && root(value))where.append("(").append(q(key)).append(" IS NULL OR ").append(q(key)).append("='' OR ").append(q(key)).append("='0')");
            else if(value==null)where.append(q(key)).append(" IS NULL");
            else {where.append(q(key)).append("=?");params.add(value);}
        }
        try(PreparedStatement ps=conn.prepareStatement("SELECT * FROM "+q(table)+" WHERE "+where)) {
            ps.setQueryTimeout(30); for(int i=0;i<params.size();i++)ps.setObject(i+1,params.get(i));
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next())return null;
                Map<String,Object> found=new LinkedHashMap<>();
                ResultSetMetaData meta=rs.getMetaData();
                for(int i=1;i<=meta.getColumnCount();i++)found.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),rs.getObject(i));
                if(rs.next())throw new IOException("匹配到多条本地数据，请配置唯一匹配字段: "+table);
                return found;
            }
        }
    }
    private static boolean deleted(Map<String,Object> row) { return row.get("del_flag")!=null && !"0".equals(row.get("del_flag").toString()); }
    public static JSONObject execute(Connection conn,Payload payload,Path work) throws Exception {
        if(!conn.getAutoCommit())throw new IOException("基础数据同步需要独立自动提交连接");
        Map<String,Table> tables=new LinkedHashMap<>();
        for(Row row:payload.rows) if(!tables.containsKey(row.table))tables.put(row.table,metadata(conn,row.table,payload.rules.getJSONObject(row.table)));
        for(Table table:new ArrayList<>(tables.values())) for(String ref:table.references.values()) if(!tables.containsKey(ref))tables.put(ref,metadata(conn,ref,payload.rules.getJSONObject(ref)));
        Map<String,Map<Object,Object>> mapped=new HashMap<>();
        Map<String,Set<Object>> sourceIds=new HashMap<>();
        for(Row row:payload.rows) {
            Table table=tables.get(row.table);
            if(!table.columns.containsAll(row.values.keySet()))throw new IOException("基础数据包含本地不存在的列: "+row.table);
            Object id=identity(row,table);
            if(!sourceIds.computeIfAbsent(row.table,k->new HashSet<>()).add(id))throw new IOException("基础数据重复主键: "+row.table+" "+id);
        }
        List<Row> pending=new ArrayList<>(payload.rows);
        JSONArray inserted=new JSONArray();int skipped=0;
        conn.setAutoCommit(false); boolean committed = false;
        try {
            while(!pending.isEmpty()) {
                int previous=pending.size();
                for(Iterator<Row> iterator=pending.iterator();iterator.hasNext();) {
                    Row row=iterator.next();Table table=tables.get(row.table);Object sourceId=identity(row,table);
                    Map<String,Object> values=new LinkedHashMap<>(row.values);boolean ready=true,blocked=deleted(values);
                    for(Map.Entry<String,String> ref:table.references.entrySet()) {
                        Object raw=values.get(ref.getKey());if(root(raw))continue;
                        Table parent=tables.get(ref.getValue());
                        if(parent.primary.size()!=1)throw new IOException("父表必须为单列主键: "+ref.getValue());
                        String id=raw.toString();
                        Map<Object,Object> ids=mapped.computeIfAbsent(ref.getValue(),k->new HashMap<>());
                        if(ids.containsKey(id)) {
                            Object actual=ids.get(id);if(actual==null)blocked=true;else values.put(ref.getKey(),actual);
                        } else if(sourceIds.getOrDefault(ref.getValue(),Collections.emptySet()).contains(id)) { ready=false;break; }
                        else {
                            Map<String,Object> local=lookup(conn,ref.getValue(),parent.primary,Collections.singletonMap(parent.primary.get(0),raw));
                            if(local==null)throw new IOException("基础数据缺少父记录，请同时导出父级: "+row.table+"."+ref.getKey()+"="+id);
                            if(deleted(local))blocked=true;
                        }
                    }
                    if(!ready)continue;
                    Map<Object,Object> ids=mapped.computeIfAbsent(row.table,k->new HashMap<>());
                    if(blocked) {ids.put(sourceId,null);skipped++;iterator.remove();continue;}
                    Row resolved=new Row();resolved.table=row.table;resolved.values.putAll(values);
                    List<String> match=keys(resolved,table);
                    if(!table.columns.containsAll(match))throw new IOException("匹配字段不存在: "+row.table);
                    Map<String,Object> existing=lookup(conn,row.table,match,values);
                    if(existing!=null) {
                        ids.put(sourceId,deleted(existing)?null:identityValue(existing,table));
                        skipped++;iterator.remove();continue;
                    }
                    if(lookup(conn,row.table,table.primary,values)!=null)throw new IOException("主键与业务键冲突，已保留本地数据: "+row.table+" "+sourceId);
                    String columns=values.keySet().stream().map(UpdateSeedData::q).collect(Collectors.joining(","));
                    String marks=String.join(",",Collections.nCopies(values.size(),"?"));
                    try(PreparedStatement ps=conn.prepareStatement("INSERT INTO "+q(row.table)+" ("+columns+") VALUES ("+marks+")")) {
                        ps.setQueryTimeout(30);int n=1;for(Object value:values.values())ps.setObject(n++,value);ps.executeUpdate();
                    }
                    if(row.table.equals("sys_permission") && !root(values.get("parent_id")) && table.columns.contains("is_leaf")) {
                        try(PreparedStatement ps=conn.prepareStatement("UPDATE sys_permission SET is_leaf=0 WHERE id=?")) {
                            ps.setObject(1,values.get("parent_id")); ps.executeUpdate();
                        }
                    }
                    ids.put(sourceId,identityValue(values,table));
                    JSONObject log=new JSONObject();log.put("table",row.table);log.put("id",sourceId);inserted.add(log);
                    iterator.remove();
                }
                if(pending.size()==previous)throw new IOException("基础数据存在循环父子依赖，停止同步");
            }
            JSONObject result=new JSONObject();result.put("inserted",inserted.size());result.put("skipped",skipped);result.put("rows",inserted);
            Path pendingResult=work.resolve("base-data-pending.json");
            Files.write(pendingResult,result.toJSONString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
            conn.commit(); committed = true;
            try { Files.move(pendingResult,work.resolve("base-data-result.json"),StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(pendingResult,work.resolve("base-data-result.json")); }
            return result;
        } catch(Exception ex) {
            if (!committed) conn.rollback();
            else throw new IOException("基础数据已提交，但结果写入失败，请核对base-data-pending.json",ex);
            throw ex;
        }
        finally {conn.setAutoCommit(true);}
    }
    private static Object identityValue(Map<String,Object> values,Table table) {
        if(table.primary.size()==1)return values.get(table.primary.get(0));
        List<String> ids=table.primary.stream().map(k->String.valueOf(values.get(k))).collect(Collectors.toList());
        return JSON.toJSONString(ids);
    }
}
