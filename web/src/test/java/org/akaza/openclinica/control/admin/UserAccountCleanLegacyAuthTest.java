package org.akaza.openclinica.control.admin;

import java.util.HashMap;
import javax.sql.DataSource;
import junit.framework.TestCase;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.domain.user.UserAccount;
import static org.mockito.Mockito.*;

public class UserAccountCleanLegacyAuthTest extends TestCase {

    private DataSource mockDataSource;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockDataSource = mock(DataSource.class);
    }

    public void testGetEntityFromHashMap_RunWebservicesTrue() {
        UserAccountDAO dao = new UserAccountDAO(mockDataSource);
        HashMap<String, Object> row = createBaseUserRow();
        row.put("run_webservices", Boolean.TRUE);

        UserAccountBean bean = (UserAccountBean) dao.getEntityFromHashMap(row, false);

        assertNotNull(bean);
        assertTrue(bean.getRunWebservices());
    }

    public void testGetEntityFromHashMap_RunWebservicesFalse() {
        UserAccountDAO dao = new UserAccountDAO(mockDataSource);
        HashMap<String, Object> row = createBaseUserRow();
        row.put("run_webservices", Boolean.FALSE);

        UserAccountBean bean = (UserAccountBean) dao.getEntityFromHashMap(row, false);

        assertNotNull(bean);
        assertFalse(bean.getRunWebservices());
    }

    public void testGetEntityFromHashMap_RunWebservicesNull() {
        UserAccountDAO dao = new UserAccountDAO(mockDataSource);
        HashMap<String, Object> row = createBaseUserRow();
        row.put("run_webservices", null);

        // This should not throw NullPointerException and should map to false defensively
        UserAccountBean bean = (UserAccountBean) dao.getEntityFromHashMap(row, false);

        assertNotNull(bean);
        assertFalse(bean.getRunWebservices());
    }

    public void testHibernateEntity_RunWebservicesDefault() {
        UserAccount entity = new UserAccount();
        assertFalse(entity.isRunWebservices());
    }

    public void testHibernateEntity_ConstructorNull() {
        UserAccount entity = new UserAccount(1, true, true, 0, null);
        assertFalse(entity.isRunWebservices());
    }

    public void testHibernateEntity_SetterNull() {
        UserAccount entity = new UserAccount();
        entity.setRunWebservices(null);
        assertFalse(entity.isRunWebservices());
    }

    private HashMap<String, Object> createBaseUserRow() {
        HashMap<String, Object> row = new HashMap<>();
        row.put("user_id", 1);
        row.put("user_name", "test.user");
        row.put("passwd", "hash");
        row.put("first_name", "Test");
        row.put("last_name", "User");
        row.put("email", "test@example.com");
        row.put("active_study", 1);
        row.put("institutional_affiliation", "None");
        row.put("status_id", 1);
        row.put("owner_id", 1);
        row.put("update_id", 1);
        row.put("date_created", new java.util.Date());
        row.put("date_updated", new java.util.Date());
        row.put("date_lastvisit", new java.util.Date());
        row.put("passwd_timestamp", new java.util.Date());
        row.put("passwd_challenge_question", "question");
        row.put("passwd_challenge_answer", "answer");
        row.put("phone", "12345678");
        row.put("user_type_id", 1);
        row.put("enabled", Boolean.TRUE);
        row.put("account_non_locked", Boolean.TRUE);
        row.put("lock_counter", 0);
        row.put("access_code", "code");
        row.put("time_zone", "EST");
        row.put("enable_api_key", Boolean.TRUE);
        row.put("api_key", "key");
        return row;
    }
}
