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
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private static final Set<String> WHITELIST = Set.of(
        "tenant-a", "tenant-b", "tenant-c", "tenant-1", "tenant-2", "tenant-alpha", "tenant-beta"
    );

    private static final Pattern STUDY_TABLE_PATTERN = Pattern.compile("(?i)(?<![\\._])\\bstudy\\b(?![\\._])");

    public static void setCurrentTenant(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static String getCurrentTenant() {
        return CURRENT_TENANT.get();
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
        BYPASS.remove();
    }

    public static String rewriteSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql;
        }

        if (isBypass()) {
            return sql;
        }

        // Check if query targets the tenant-isolated STUDY table
        if (!STUDY_TABLE_PATTERN.matcher(sql).find()) {
            return sql;
        }

        String trimmed = sql.trim();
        String upper = trimmed.toUpperCase();

        // Allow DDL statements without tenant filtering
        if (upper.startsWith("CREATE") || upper.startsWith("DROP") || upper.startsWith("ALTER") || upper.startsWith("TRUNCATE")) {
            return sql;
        }

        // If query already contains explicit tenant_id column or filter, return as-is
        if (upper.contains("TENANT_ID = ") || upper.contains("TENANT_ID=") || upper.contains("TENANT_ID IS") || upper.contains(", TENANT_ID")) {
            return sql;
        }

        String tenantId = getCurrentTenant();
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalStateException("Missing active tenant context for tenant-isolated query");
        }

        // 1. INSERT statement
        if (upper.startsWith("INSERT INTO STUDY") || upper.startsWith("INSERT INTO \"STUDY\"")) {
            int valuesIndex = upper.indexOf("VALUES");
            if (valuesIndex != -1) {
                String colsPart = trimmed.substring(0, valuesIndex).trim();
                String valsPart = trimmed.substring(valuesIndex).trim();

                int lastParenCol = colsPart.lastIndexOf(')');
                if (lastParenCol != -1) {
                    colsPart = colsPart.substring(0, lastParenCol) + ", tenant_id" + colsPart.substring(lastParenCol);
                }

                int lastParenVal = valsPart.lastIndexOf(')');
                if (lastParenVal != -1) {
                    valsPart = valsPart.substring(0, lastParenVal) + ", '" + tenantId.replace("'", "''") + "'" + valsPart.substring(lastParenVal);
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
                    Expression predicate = createTenantPredicate(qualifier, tenantId);
                    if (!hasTenantPredicate(update.getWhere(), qualifier)) {
                        update.setWhere(addAndPredicate(update.getWhere(), predicate));
                    }
                    return update.toString();
                }
            } else if (stmt instanceof Delete) {
                Delete delete = (Delete) stmt;
                if (isTenantRestrictedTable(delete.getTable())) {
                    String qualifier = getQualifier(delete.getTable());
                    Expression predicate = createTenantPredicate(qualifier, tenantId);
                    if (!hasTenantPredicate(delete.getWhere(), qualifier)) {
                        delete.setWhere(addAndPredicate(delete.getWhere(), predicate));
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
                Expression predicate = createTenantPredicate(qualifier, tenantId);
                if (!hasTenantPredicate(plainSelect.getWhere(), qualifier)) {
                    plainSelect.setWhere(addAndPredicate(plainSelect.getWhere(), predicate));
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
        if (table == null || table.getName() == null) return false;
        String name = table.getName().replaceAll("^\"|\"$", "");
        return "study".equalsIgnoreCase(name);
    }

    private static String getQualifier(Table table) {
        if (table.getAlias() != null && table.getAlias().getName() != null && !table.getAlias().getName().trim().isEmpty()) {
            return table.getAlias().getName().trim();
        }
        return table.getName().replaceAll("^\"|\"$", "");
    }

    private static Expression createTenantPredicate(String qualifier, String tenantId) {
        String sanitizedTenantId = tenantId.replace("'", "''");
        Column col = new Column(new Table(qualifier), "tenant_id");
        return new EqualsTo(col, new StringValue(sanitizedTenantId));
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
}
