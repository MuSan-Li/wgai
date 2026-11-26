package org.jeecg.modules.demo.config.service.impl;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.*;
import org.jeecg.modules.demo.config.entity.TabUpdateConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Compare parsed CREATE TABLE definitions; never execute the downloaded dump. */
public final class UpdateSchema {
    private UpdateSchema() { }
    public static class Plan {
        public final List<String> statements = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }
    private static String name(String s) {
        String n = s.replace("`", "").trim();
        if (!n.matches("[a-zA-Z0-9_$]+")) throw new IllegalArgumentException("不支持带库名或特殊字符的表/字段: " + s);
        return n;
    }
    private static String quote(String s) { return "`" + name(s) + "`"; }
    private static SQLCreateTableStatement create(String sql) {
        List<SQLStatement> list = SQLUtils.parseStatements(sql, "mysql");
        if (list.size() != 1 || !(list.get(0) instanceof SQLCreateTableStatement)) throw new IllegalArgumentException("无效的CREATE TABLE");
        SQLCreateTableStatement c = (SQLCreateTableStatement) list.get(0);
        name(c.getName().toString()); return c;
    }
    private static Map<String, SQLColumnDefinition> columns(SQLCreateTableStatement c) {
        Map<String, SQLColumnDefinition> result = new LinkedHashMap<>();
        for (SQLTableElement e : c.getTableElementList()) if (e instanceof SQLColumnDefinition) {
            SQLColumnDefinition col = (SQLColumnDefinition) e;
            result.put(name(col.getName().toString()), col);
        }
        return result;
    }
    // Normalize SQL tokens, while preserving the case and spaces inside string literals.
    private static String canonical(String sql) {
        StringBuilder out = new StringBuilder(); char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < sql.length()) out.append(sql.charAt(++i));
                else if (c == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) out.append(sql.charAt(++i));
                    else quote = 0;
                }
            } else if (c == '\'' || c == '"') { quote = c; out.append(c); }
            else if (c != '`' && !Character.isWhitespace(c)) out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }
    private static Set<String> constraints(SQLCreateTableStatement c) {
        Set<String> result = new TreeSet<>();
        for (SQLTableElement e : c.getTableElementList()) if (!(e instanceof SQLColumnDefinition)) result.add(canonical(SQLUtils.toSQLString(e, "mysql")));
        return result;
    }
    public static Plan plan(Connection conn, String source, TabUpdateConfig cfg, Path work) throws Exception {
        if (!conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")) throw new IOException("结构同步目前支持MySQL");
        Map<String, SQLCreateTableStatement> target = new LinkedHashMap<>();
        boolean skippedUpdateConfig = false;
        for (SQLStatement s : SQLUtils.parseStatements(source, "mysql")) {
            if (s instanceof SQLCreateTableStatement) {
                SQLCreateTableStatement c = (SQLCreateTableStatement) s;
                if (c.getSelect() != null || c.getLike() != null || c.getTableElementList().isEmpty()) throw new IOException("只允许显式表结构定义，禁止CREATE TABLE AS SELECT / LIKE");
                String n = name(c.getName().toString());
                if ("tab_update_config".equalsIgnoreCase(n)) { skippedUpdateConfig = true; continue; }
                if (target.put(n, c) != null) throw new IOException("结构文件包含重复表: " + n);
            } else {
                // Standard dump wrappers are ignored, never executed. Reject data and executable routines.
                String type = s.getClass().getSimpleName();
                if (!(type.equals("SQLSetStatement") || type.equals("SQLUseStatement") || type.equals("SQLDropTableStatement"))) {
                    throw new IOException("结构文件仅允许CREATE TABLE及导出包装语句，不支持: " + type);
                }
            }
        }
        if (target.isEmpty()) throw new IOException("结构文件没有CREATE TABLE");
        String prefix = cfg.getDbTablePrefix() == null ? "" : cfg.getDbTablePrefix().trim();
        if (!prefix.isEmpty() && !prefix.matches("[a-zA-Z0-9_$]+")) throw new IOException("表名前缀不合法");
        Map<String, SQLCreateTableStatement> local = new LinkedHashMap<>();
        StringBuilder backup = new StringBuilder("-- Structure only; take a separate data backup before destructive DDL.\n");
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SHOW FULL TABLES WHERE Table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                String table = rs.getString(1);
                if (table.equalsIgnoreCase("tab_update_config") || !table.startsWith(prefix)) continue;
                try (Statement show = conn.createStatement(); ResultSet cr = show.executeQuery("SHOW CREATE TABLE " + quote(table))) {
                    cr.next(); String ddl = cr.getString(2); local.put(table, create(ddl)); backup.append(ddl).append(";\n\n");
                }
            }
        }
        Files.write(work.resolve("schema-before.sql"), backup.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        Plan plan = new Plan(); int included = 0;
        if (skippedUpdateConfig) plan.warnings.add("已保留本地更新配置表tab_update_config，不参与结构同步");
        for (Map.Entry<String, SQLCreateTableStatement> entry : target.entrySet()) {
            String table = entry.getKey(); if (!table.startsWith(prefix)) continue; included++;
            SQLCreateTableStatement desired = entry.getValue(); SQLCreateTableStatement old = local.get(table);
            if (old == null) { plan.statements.add(desired.toString()); continue; }
            if (!constraints(old).equals(constraints(desired))) throw new IOException("表 " + table + " 的索引/约束不同，请先单独迁移；本接口只同步表和字段");
            Map<String, SQLColumnDefinition> a = columns(old), b = columns(desired);
            for (Map.Entry<String, SQLColumnDefinition> col : b.entrySet()) {
                String definition = col.getValue().toString();
                if (!a.containsKey(col.getKey())) plan.statements.add("ALTER TABLE " + quote(table) + " ADD COLUMN " + definition);
                else if (!canonical(a.get(col.getKey()).toString()).equals(canonical(definition))) {
                    if (Integer.valueOf(1).equals(cfg.getDbAllowModifyColumn())) plan.statements.add("ALTER TABLE " + quote(table) + " MODIFY COLUMN " + definition);
                    else plan.warnings.add("保留字段定义: " + table + "." + col.getKey());
                }
            }
            for (String col : a.keySet()) if (!b.containsKey(col)) {
                if (Integer.valueOf(1).equals(cfg.getDbAllowDropColumn())) plan.statements.add("ALTER TABLE " + quote(table) + " DROP COLUMN " + quote(col));
                else plan.warnings.add("保留本地字段: " + table + "." + col);
            }
        }
        if (included == 0) throw new IOException("表名前缀未匹配到任何目标表，停止同步");
        for (String table : local.keySet()) if (!target.containsKey(table)) {
            if (Integer.valueOf(1).equals(cfg.getDbAllowDropTable())) plan.statements.add("DROP TABLE " + quote(table));
            else plan.warnings.add("保留本地表: " + table);
        }
        Files.write(work.resolve("schema-diff.sql"), String.join(";\n", plan.statements).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        return plan;
    }
    public static void execute(Connection conn, Plan plan, Path work) throws Exception {
        Path journal = work.resolve("schema-execution.log");
        for (String ddl : plan.statements) {
            Files.write(journal, ("START " + ddl + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            try (Statement st = conn.createStatement()) { st.setQueryTimeout(120); st.execute(ddl); }
            Files.write(journal, "SUCCESS\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }
}