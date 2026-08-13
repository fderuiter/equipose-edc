package org.akaza.openclinica.modern.security;

import java.util.Set;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    private static final Set<String> WHITELIST = Set.of(
        "tenant-a", "tenant-b", "tenant-c", "tenant-1", "tenant-2", "tenant-alpha", "tenant-beta"
    );

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
        String tenantId = getCurrentTenant();
        if (tenantId == null || isBypass()) {
            return sql;
        }
        if (sql == null) {
            return null;
        }
        String trimmed = sql.trim().replaceAll("\\s+", " ");
        String upper = trimmed.toUpperCase();

        // Only filter queries targeting the STUDY table
        if (!upper.contains("STUDY")) {
            return sql;
        }

        // Avoid double-wrapping or infinite recursion if tenant_id filter is already injected
        if (upper.contains("TENANT_ID = ") || upper.contains("TENANT_ID=")) {
            return sql;
        }

        // 1. INSERT statement
        if (upper.startsWith("INSERT INTO STUDY")) {
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
                    valsPart = valsPart.substring(0, lastParenVal) + ", '" + tenantId + "'" + valsPart.substring(lastParenVal);
                }
                return colsPart + " " + valsPart;
            }
        }

        // 2. UPDATE/DELETE statement
        if (upper.startsWith("UPDATE STUDY") || upper.startsWith("DELETE FROM STUDY")) {
            if (upper.contains("WHERE")) {
                return trimmed + " AND tenant_id = '" + tenantId + "'";
            } else {
                return trimmed + " WHERE tenant_id = '" + tenantId + "'";
            }
        }

        // 3. SELECT statement
        if (upper.startsWith("SELECT")) {
            // Replace standalone word "study" case-insensitively with a subquery
            // ensuring we don't replace if preceded/followed by dot or underscore
            String subquery = "(SELECT * FROM study WHERE tenant_id = '" + tenantId + "')";
            return trimmed.replaceAll("(?i)(?<![\\._])\\bstudy\\b(?![\\._])", subquery);
        }

        return sql;
    }
}
