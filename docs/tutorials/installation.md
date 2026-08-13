# Installation Guide

Welcome to the installation guide for OpenClinica. This guide will walk you through the manual steps required to set up a clean deployment on a Linux server. By the end of this tutorial, you will have a running instance configured with the modern system baseline.

## System Prerequisites

Before beginning the installation, ensure that your environment meets the strict baseline requirements. Legacy configurations are no longer supported.

- **Java Development Kit:** JDK 17 is strictly required.
- **Database:** PostgreSQL 15 must be installed and running.
- **Application Server:** Tomcat 9 is necessary to host the application.

## Manual Execution Steps

Follow these manual instructions to complete the clean deployment.

### 1. Database Configuration

Start by preparing your database. Open your PostgreSQL 15 shell and execute the following manual commands to create the dedicated database and user:

```sql
CREATE DATABASE clinica;
CREATE USER clinica WITH PASSWORD 'clinica';
GRANT ALL PRIVILEGES ON DATABASE clinica TO clinica;
```

### 2. Application Server Setup

Next, configure Tomcat 9. Navigate to your Tomcat 9 installation directory and ensure that the memory settings are appropriate for your environment. We rely on the modern garbage collector in JDK 17, so avoid using deprecated JVM flags in your environment variables.

### 3. Deploying the Application

Once the database and application server are ready, place the application package into the Tomcat 9 webapps directory.

```bash
cp openclinica.war /opt/tomcat9/webapps/
```

Restart the Tomcat 9 service to initiate the deployment.

```bash
systemctl restart tomcat9
```

### 4. Verification

Finally, verify that the deployment was successful by navigating to the application root URL in your browser. The initial startup may take a few minutes as the database schema is initialized.

```bash
curl http://localhost:8080/openclinica
```

Congratulations! You have successfully completed the manual deployment process for OpenClinica.

## Configuration Keys Reference
The following configuration properties are recognized:
`OpenClinica.version`, `adminEmail`, `catalina.home`, `ccts.waitBeforeCommit`, `collectStats`, `db`, `db1.password`, `db1.url`, `db1.username`, `dbHost`, `dbPass`, `dbPort`, `dbType`, `dbUser`, `designerURL`, `extract.1.exportname`, `extract.1.file`, `extract.1.fileDescription`, `extract.1.helpText`, `extract.1.linkText`, `extract.1.location`, `extract.1.odmType`, `extract.1.success`, `extract.10.deleteOld`, `extract.10.exportname`, `extract.10.failure`, `extract.10.file`, `extract.10.fileDescription`, `extract.10.helpText`, `extract.10.linkText`, `extract.10.location`, `extract.10.odmType`, `extract.10.success`, `extract.10.zip`, `extract.10.zipName`, `extract.11.exportname`, `extract.11.file`, `extract.11.fileDescription`, `extract.11.helpText`, `extract.11.linkText`, `extract.11.location`, `extract.11.odmType`, `extract.11.post`, `extract.11.success`, `extract.2.exportname`, `extract.2.file`, `extract.2.fileDescription`, `extract.2.helpText`, `extract.2.linkText`, `extract.2.location`, `extract.2.odmType`, `extract.2.success`, `extract.3.exportname`, `extract.3.file`, `extract.3.fileDescription`, `extract.3.helpText`, `extract.3.linkText`, `extract.3.location`, `extract.3.odmType`, `extract.3.success`, `extract.4.exportname`, `extract.4.file`, `extract.4.fileDescription`, `extract.4.helpText`, `extract.4.linkText`, `extract.4.location`, `extract.4.odmType`, `extract.4.success`, `extract.5.exportname`, `extract.5.file`, `extract.5.fileDescription`, `extract.5.helpText`, `extract.5.linkText`, `extract.5.location`, `extract.5.odmType`, `extract.5.success`, `extract.6.exportname`, `extract.6.file`, `extract.6.fileDescription`, `extract.6.helpText`, `extract.6.linkText`, `extract.6.location`, `extract.6.odmType`, `extract.6.success`, `extract.7.exportname`, `extract.7.file`, `extract.7.fileDescription`, `extract.7.helpText`, `extract.7.linkText`, `extract.7.location`, `extract.7.odmType`, `extract.7.success`, `extract.8.exportname`, `extract.8.file`, `extract.8.fileDescription`, `extract.8.helpText`, `extract.8.linkText`, `extract.8.location`, `extract.8.odmType`, `extract.8.success`, `extract.9.exportname`, `extract.9.file`, `extract.9.fileDescription`, `extract.9.helpText`, `extract.9.linkText`, `extract.9.location`, `extract.9.odmType`, `extract.9.success`, `extract.9.zipName`, `extract.number`, `filePath`, `help.base.url`, `json1.exportname`, `json1.location`, `json1.postProcessor`, `ldap.enabled`, `ldap.host`, `ldap.loginQuery`, `ldap.password`, `ldap.passwordRecoveryURL`, `ldap.userData.distinguishedName`, `ldap.userData.email`, `ldap.userData.firstName`, `ldap.userData.lastName`, `ldap.userData.organization`, `ldap.userData.username`, `ldap.userDn`, `ldap.userSearch.baseDn`, `ldap.userSearch.query`, `log.dir`, `logLevel`, `logLocation`, `mailErrorMsg`, `mailHost`, `mailPassword`, `mailPort`, `mailProtocol`, `mailSmtpAuth`, `mailSmtpConnectionTimeout`, `mailSmtpSslProtocols`, `mailSmtpStarttls.enable`, `mailSmtpStarttls.required`, `mailSmtpsAuth`, `mailSmtpsStarttls.enable`, `mailUsername`, `maxInactiveInterval`, `org.quartz.jobStore.misfireThreshold`, `org.quartz.threadPool.threadCount`, `org.quartz.threadPool.threadPriority`, `pdf1.deleteOld`, `pdf1.exportname`, `pdf1.location`, `pdf1.postProcessor`, `pdf1.zip`, `rssMore`, `rssUrl`, `ruleDesignerURL`, `sysURL`, `syslog.host`, `syslog.port`, `usage.stats.host`, `usage.stats.port`, `userAccountNotification`, `randomization.enabled`.

## Database Schema Tables Reference
These tables are initialized during a clean installation:
`audit_log_event`, `audit_log_event_type`, `clinical_records`, `configuration_drafts`, `dde_records`.
