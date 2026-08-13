package org.akaza.openclinica.modern.security;

import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.login.StudyUserRoleBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;

@Component("studySecurityValidator")
public class StudySecurityValidator {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public boolean hasAdminOrCoordinatorRole(String uniqueProtocolID) {
        ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attr.getRequest();
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }

        UserAccountBean userBean = (UserAccountBean) session.getAttribute("userBean");
        if (userBean == null) {
            return false;
        }

        // Global SysAdmin check
        if (userBean.isSysAdmin()) {
            return true;
        }

        // Check if study exists globally (bypassing tenant filter)
        org.akaza.openclinica.modern.security.TenantContext.setBypass(true);
        List<Integer> globalIds;
        try {
            globalIds = jdbcTemplate.queryForList("SELECT study_id FROM study WHERE unique_identifier = ?", Integer.class, uniqueProtocolID);
        } finally {
            org.akaza.openclinica.modern.security.TenantContext.setBypass(false);
        }

        if (globalIds.isEmpty()) {
            throw new IllegalArgumentException("Study not found");
        }

        // Find the study ID from uniqueProtocolID in the current tenant's context
        List<Integer> studyIds = jdbcTemplate.queryForList("SELECT study_id FROM study WHERE unique_identifier = ?", Integer.class, uniqueProtocolID);
        if (studyIds.isEmpty()) {
            // Study exists globally but not in this tenant context, obscure its existence by throwing study not found!
            throw new IllegalArgumentException("Study not found");
        }
        Integer studyId = studyIds.get(0);

        StudyUserRoleBean roleBean = userBean.getRoleByStudy(studyId);
        if (roleBean == null || !roleBean.isActive()) {
            return false;
        }

        Role role = roleBean.getRole();
        if (role == null) {
            return false;
        }

        return role == Role.ADMIN || role == Role.COORDINATOR;
    }
}
