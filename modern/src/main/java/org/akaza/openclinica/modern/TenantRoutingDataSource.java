package org.akaza.openclinica.modern;

import org.akaza.openclinica.modern.security.TenantContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class TenantRoutingDataSource extends DelegatingDataSource {

    public TenantRoutingDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return createConnectionProxy(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return createConnectionProxy(super.getConnection(username, password));
    }

    private Connection createConnectionProxy(Connection delegate) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("prepareStatement".equals(name) && args != null && args.length > 0 && args[0] instanceof String) {
                        args[0] = TenantContext.rewriteSql((String) args[0]);
                    }
                    if ("createStatement".equals(name)) {
                        Statement stmt = (Statement) method.invoke(delegate, args);
                        return createStatementProxy(stmt);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    private Statement createStatementProxy(Statement delegate) {
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (("executeQuery".equals(name) || "executeUpdate".equals(name) || "execute".equals(name) || "addBatch".equals(name))
                            && args != null && args.length > 0 && args[0] instanceof String) {
                        args[0] = TenantContext.rewriteSql((String) args[0]);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }
}
