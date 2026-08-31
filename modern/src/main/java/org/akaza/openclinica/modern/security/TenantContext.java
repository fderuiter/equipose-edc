package org.akaza.openclinica.modern.security;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;

import java.util.Set;
import java.util.regex.Pattern;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_STUDY = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private static final Set<String> WHITELIST = Set.of(
        "tenant-a", "tenant-b", "tenant-c", "tenant-1", "tenant-2", "tenant-alpha", "tenant-beta"
    );

    private static final Pattern STUDY_TABLE_PATTERN = Pattern.compile("(?i)(?<![\\._])\\b(study|dde_records)\\b(?![\\._])");

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
    }

    public static void setCurrentStudy(Integer studyId) {
        CURRENT_STUDY.set(studyId);
    }

    public static Integer getCurrentStudy() {
        return CURRENT_STUDY.get();
    }

    public static void setBypass(boolean bypass) {
        BYPASS.set(bypass);
    }

    public static boolean isBypass() {
        return BYPASS.get() != null && BYPASS.get();
    }

    public static boolean isWhitelisted(String tenantId) {
        return tenantId != null && WHITELIST.contains(tenantId);
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_STUDY.remove();
        BYPASS.remove();
    }

    public static String rewriteSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        if (isBypass()) {
            return sql;
        }

        // Check if query targets tenant-isolated tables (study or dde_records)
        if (!STUDY_TABLE_PATTERN.matcher(sql).find()) {
            return sql;
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        // Allow DDL statements without tenant filtering
        if (upper.startsWith("CREATE") || upper.startsWith("DROP") || upper.startsWith("ALTER") || upper.startsWith("TRUNCATE")) {
            return sql;
        }

        String tenantId = getCurrentTenant();

        // 1. INSERT statement
        if (upper.startsWith("INSERT INTO STUDY") || upper.startsWith("INSERT INTO \"STUDY\"") ||
            upper.startsWith("INSERT INTO DDE_RECORDS") || upper.startsWith("INSERT INTO \"DDE_RECORDS\"")) {
            int valuesIndex = upper.indexOf("VALUES");
            if (valuesIndex != -1) {
                String colsPart = trimmed.substring(0, valuesIndex).trim();
                String valsPart = trimmed.substring(valuesIndex).trim();

                int lastParenCol = colsPart.lastIndexOf(')');
                int lastParenVal = valsPart.lastIndexOf(')');

                if (lastParenCol != -1 && lastParenVal != -1) {
                    String colsToAdd = "";
                    String valsToAdd = "";

                    if (!colsPart.toUpperCase().contains("TENANT_ID") && tenantId != null && !tenantId.trim().isEmpty()) {
                        colsToAdd += ", tenant_id";
                        valsToAdd += ", '" + tenantId.replace("'", "''") + "'";
                    }

                    if (upper.contains("DDE_RECORDS") && !colsPart.toUpperCase().contains("STUDY_ID") && getCurrentStudy() != null) {
                        colsToAdd += ", study_id";
                        valsToAdd += ", " + getCurrentStudy();
                    }

                    if (!colsToAdd.isEmpty()) {
                        colsPart = colsPart.substring(0, lastParenCol) + colsToAdd + colsPart.substring(lastParenCol);
                        valsPart = valsPart.substring(0, lastParenVal) + valsToAdd + valsPart.substring(lastParenVal);
                    }
                }
                return colsPart + " " + valsPart;
            }
        }

        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof Select) {
                processSelect((Select) stmt, tenantId);
                return stmt.toString();
            } else if (stmt instanceof Update) {
                Update update = (Update) stmt;
                if (isTenantRestrictedTable(update.getTable())) {
                    String qualifier = getQualifier(update.getTable());
                    if (tenantId != null && !tenantId.trim().isEmpty() && !hasTenantPredicate(update.getWhere(), qualifier)) {
                        Expression predicate = createTenantPredicate(qualifier, tenantId);
                        update.setWhere(addAndPredicate(update.getWhere(), predicate));
                    }
                    if ("dde_records".equalsIgnoreCase(getTableName(update.getTable())) && getCurrentStudy() != null && !hasStudyPredicate(update.getWhere(), qualifier)) {
                        Expression studyPredicate = createStudyPredicate(qualifier, getCurrentStudy());
                        update.setWhere(addAndPredicate(update.getWhere(), studyPredicate));
                    }
                    return update.toString();
                }
            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                if (isTenantRestrictedTable(delete.getTable())) {
                    String qualifier = getQualifier(delete.getTable());
                    if (tenantId != null && !tenantId.trim().isEmpty() && !hasTenantPredicate(delete.getWhere(), qualifier)) {
                        Expression predicate = createTenantPredicate(qualifier, tenantId);
                        delete.setWhere(addAndPredicate(delete.getWhere(), predicate));
                    }
                    if ("dde_records".equalsIgnoreCase(getTableName(delete.getTable())) && getCurrentStudy() != null && !hasStudyPredicate(delete.getWhere(), qualifier)) {
                        Expression studyPredicate = createStudyPredicate(qualifier, getCurrentStudy());
                        delete.setWhere(addAndPredicate(delete.getWhere(), studyPredicate));
                    }
                    return delete.toString();
                }
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Failed to parse SQL query for tenant isolation: " + sql, e);
        }

        return sql;
    }

    @SuppressWarnings("deprecation")
    private static void processSelect(Select select, String tenantId) {
        if (select == null) return;
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    processSelect(withItem.getSelect(), tenantId);
                }
            }
        }

        Object body = select.getSelectBody();
        if (body instanceof PlainSelect) {
            processPlainSelect((PlainSelect) body, tenantId);
        } else if (body instanceof SetOperationList) {
            processSetOperationList((SetOperationList) body, tenantId);
        } else if (body instanceof ParenthesedSelect) {
            ParenthesedSelect ps = (ParenthesedSelect) body;
            if (ps.getSelect() != null) {
                processSelect(ps.getSelect(), tenantId);
            }
        }
    }

    private static void processSetOperationList(SetOperationList setOpList, String tenantId) {
        if (setOpList == null || setOpList.getSelects() == null) return;
        for (Object obj : setOpList.getSelects()) {
            if (obj instanceof PlainSelect) {
                processPlainSelect((PlainSelect) obj, tenantId);
            } else if (obj instanceof Select) {
                processSelect((Select) obj, tenantId);
            } else if (obj instanceof SetOperationList) {
                processSetOperationList((SetOperationList) obj, tenantId);
            } else if (obj instanceof ParenthesedSelect) {
                ParenthesedSelect ps = (ParenthesedSelect) obj;
                if (ps.getSelect() != null) {
                    processSelect(ps.getSelect(), tenantId);
                }
            }
        }
    }

    private static void processPlainSelect(PlainSelect plainSelect, String tenantId) {
        if (plainSelect == null) return;

        if (plainSelect.getWithItemsList() != null) {
            for (WithItem withItem : plainSelect.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    processSelect(withItem.getSelect(), tenantId);
                }
            }
        }

        if (plainSelect.getSelectItems() != null) {
            for (SelectItem<?> selectItem : plainSelect.getSelectItems()) {
                if (selectItem.getExpression() != null) {
                    processExpression(selectItem.getExpression(), tenantId);
                }
            }
        }

        FromItem fromItem = plainSelect.getFromItem();
        if (fromItem != null) {
            processFromItem(fromItem, plainSelect, tenantId);
        }

        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                processJoin(join, plainSelect, tenantId);
            }
        }

        if (plainSelect.getWhere() != null) {
            processExpression(plainSelect.getWhere(), tenantId);
        }
        if (plainSelect.getHaving() != null) {
            processExpression(plainSelect.getHaving(), tenantId);
        }
    }

    private static void processFromItem(FromItem fromItem, PlainSelect plainSelect, String tenantId) {
        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            if (isTenantRestrictedTable(table)) {
                String qualifier = getQualifier(table);
                if (tenantId != null && !tenantId.trim().isEmpty() && !hasTenantPredicate(plainSelect.getWhere(), qualifier)) {
                    Expression predicate = createTenantPredicate(qualifier, tenantId);
                    plainSelect.setWhere(addAndPredicate(plainSelect.getWhere(), predicate));
                }
                if ("dde_records".equalsIgnoreCase(getTableName(table)) && getCurrentStudy() != null && !hasStudyPredicate(plainSelect.getWhere(), qualifier)) {
                    Expression studyPredicate = createStudyPredicate(qualifier, getCurrentStudy());
                    plainSelect.setWhere(addAndPredicate(plainSelect.getWhere(), studyPredicate));
                }
            }
        } else if (fromItem instanceof ParenthesedSelect) {
            ParenthesedSelect ps = (ParenthesedSelect) fromItem;
            if (ps.getSelect() != null) {
                processSelect(ps.getSelect(), tenantId);
            }
        } else if (fromItem instanceof ParenthesedFromItem) {
            ParenthesedFromItem pfi = (ParenthesedFromItem) fromItem;
            if (pfi.getFromItem() != null) {
                processFromItem(pfi.getFromItem(), plainSelect, tenantId);
            }
            if (pfi.getJoins() != null) {
                for (Join join : pfi.getJoins()) {
                    processJoin(join, plainSelect, tenantId);
                }
            }
        }
    }

    private static void processJoin(Join join, PlainSelect plainSelect, String tenantId) {
        if (join == null) return;
        FromItem rightItem = join.getRightItem();
        if (rightItem instanceof Table) {
            Table table = (Table) rightItem;
            if (isTenantRestrictedTable(table)) {
                String qualifier = getQualifier(table);
                if (tenantId != null && !tenantId.trim().isEmpty()) {
                    Expression predicate = createTenantPredicate(qualifier, tenantId);
                    boolean alreadyHas = false;
                    if (join.getOnExpressions() != null) {
                        for (Expression onExpr : join.getOnExpressions()) {
                            if (hasTenantPredicate(onExpr, qualifier)) {
                                alreadyHas = true;
                                break;
                            }
                        }
                    }
                    if (!alreadyHas) {
                        if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                            join.addOnExpression(predicate);
                        } else {
                            if (join.isSimple() || join.isCross()) {
                                if (!hasTenantPredicate(plainSelect.getWhere(), qualifier)) {
                                    plainSelect.setWhere(addAndPredicate(plainSelect.getWhere(), predicate));
                                }
                            } else {
                                join.addOnExpression(predicate);
                            }
                        }
                    }
                }

                if ("dde_records".equalsIgnoreCase(getTableName(table)) && getCurrentStudy() != null) {
                    Expression studyPredicate = createStudyPredicate(qualifier, getCurrentStudy());
                    boolean alreadyHasStudy = false;
                    if (join.getOnExpressions() != null) {
                        for (Expression onExpr : join.getOnExpressions()) {
                            if (hasStudyPredicate(onExpr, qualifier)) {
                                alreadyHasStudy = true;
                                break;
                            }
                        }
                    }
                    if (!alreadyHasStudy) {
                        if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                            join.addOnExpression(studyPredicate);
                        } else {
                            if (join.isSimple() || join.isCross()) {
                                if (!hasStudyPredicate(plainSelect.getWhere(), qualifier)) {
                                    plainSelect.setWhere(addAndPredicate(plainSelect.getWhere(), studyPredicate));
                                }
                            } else {
                                join.addOnExpression(studyPredicate);
                            }
                        }
                    }
                }
            }
        } else if (rightItem instanceof ParenthesedSelect) {
            ParenthesedSelect ps = (ParenthesedSelect) rightItem;
            if (ps.getSelect() != null) {
                processSelect(ps.getSelect(), tenantId);
            }
        } else if (rightItem instanceof ParenthesedFromItem) {
            ParenthesedFromItem pfi = (ParenthesedFromItem) rightItem;
            if (pfi.getFromItem() != null) {
                processFromItem(pfi.getFromItem(), plainSelect, tenantId);
            }
            if (pfi.getJoins() != null) {
                for (Join j : pfi.getJoins()) {
                    processJoin(j, plainSelect, tenantId);
                }
            }
        }

        if (join.getOnExpressions() != null) {
            for (Expression onExpr : join.getOnExpressions()) {
                processExpression(onExpr, tenantId);
            }
        }
    }

    private static void processExpression(Expression expr, String tenantId) {
        if (expr == null) return;
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(ParenthesedSelect parenthesedSelect) {
                if (parenthesedSelect.getSelect() != null) {
                    processSelect(parenthesedSelect.getSelect(), tenantId);
                }
                super.visit(parenthesedSelect);
            }
        });
    }

    private static boolean isTenantRestrictedTable(Table table) {
        String name = getTableName(table);
        return "study".equalsIgnoreCase(name) || "dde_records".equalsIgnoreCase(name);
    }

    private static String getTableName(Table table) {
        if (table == null || table.getName() == null) return "";
        return table.getName().replaceAll("^\"|\"$", "");
    }

    private static String getQualifier(Table table) {
        if (table.getAlias() != null && table.getAlias().getName() != null && !table.getAlias().getName().trim().isEmpty()) {
            return table.getAlias().getName().trim();
        }
        return getTableName(table);
    }

    private static Expression createTenantPredicate(String qualifier, String tenantId) {
        String sanitizedTenantId = tenantId.replace("'", "''");
        Column col = new Column(new Table(qualifier), "tenant_id");
        return new EqualsTo(col, new StringValue(sanitizedTenantId));
    }

    private static Expression createStudyPredicate(String qualifier, Integer studyId) {
        Column col = new Column(new Table(qualifier), "study_id");
        return new EqualsTo(col, new LongValue(studyId));
    }

    private static Expression addAndPredicate(Expression currentWhere, Expression predicate) {
        if (currentWhere == null) {
            return predicate;
        }
        return new AndExpression(currentWhere, predicate);
    }

    private static boolean hasTenantPredicate(Expression expr, String qualifier) {
        if (expr == null) return false;
        String exprStr = expr.toString().toUpperCase();
        return exprStr.contains("TENANT_ID =") || exprStr.contains("TENANT_ID=") || exprStr.contains("TENANT_ID IS");
    }

    private static boolean hasStudyPredicate(Expression expr, String qualifier) {
        if (expr == null) return false;
        String exprStr = expr.toString().toUpperCase();
        return exprStr.contains("STUDY_ID =") || exprStr.contains("STUDY_ID=") || exprStr.contains("STUDY_ID IS");
    }
}
