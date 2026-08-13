package org.akaza.openclinica.modern;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

@Configuration
public class DataSourceWrapperConfig {

    @Bean
    public BeanPostProcessor dataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("dataSource".equals(beanName) && bean instanceof DataSource && !(bean instanceof TransactionAwareDataSourceProxy)) {
                    DataSource ds = (DataSource) bean;
                    try (java.sql.Connection conn = ds.getConnection();
                         java.sql.Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE study ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255)");
                    } catch (Exception e) {
                        System.err.println("Could not add tenant_id column to study table: " + e.getMessage());
                    }
                    TenantRoutingDataSource tenantDS = new TenantRoutingDataSource(ds);
                    return new TransactionAwareDataSourceProxy(tenantDS);
                }
                return bean;
            }
        };
    }
}
