package org.akaza.openclinica.modern;

import org.akaza.openclinica.bean.login.StudyUserRoleBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.modern.dto.DdeValidationRequest;
import org.akaza.openclinica.modern.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/dde")
public class DdeController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostMapping("/validate")
    public ResponseEntity<String> validateDde(@Valid @RequestBody DdeValidationRequest payload, HttpServletRequest request) {
        if (payload == null || payload.getStudyId() == null) {
            return ResponseEntity.badRequest().body("studyId is required");
        }

        Integer studyId = payload.getStudyId();

        if (!isStudyAuthorized(studyId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access Denied: Unauthorized study ID");
        }

        Integer previousStudy = TenantContext.getCurrentStudy();
        try {
            TenantContext.setCurrentStudy(studyId);

            String subjectOid = payload.getSubjectOid();
            String itemOid = payload.getItemOid();
            String value = payload.getValue();
            boolean override = Boolean.parseBoolean(payload.getOverride() != null ? payload.getOverride() : "false");

            // Check if record exists for this subject, item, and study
            String checkSql = "SELECT * FROM dde_records WHERE subject_oid = ? AND item_oid = ? AND study_id = ?";
            List<Map<String, Object>> records = jdbcTemplate.queryForList(checkSql, subjectOid, itemOid, studyId);

            if (records.isEmpty()) {
                // First entry (initial entry) - we store it as reference
                String insertSql = "INSERT INTO dde_records (id, subject_oid, item_oid, first_value, submission_count, study_id) VALUES (?, ?, ?, ?, ?, ?)";
                jdbcTemplate.update(insertSql, UUID.randomUUID().toString(), subjectOid, itemOid, value, 1, studyId);
                return ResponseEntity.ok("First entry saved");
            } else {
                // Second entry (verification)
                Map<String, Object> record = records.get(0);
                String firstValue = (String) record.get("first_value");
                
                if (!firstValue.equals(value) && !override) {
                    // Mismatch on first submission of double entry
                    return ResponseEntity.status(HttpStatus.CONFLICT).body("Mismatch detected. Provide override to force save.");
                }

                // Successful double entry or overridden
                String updateSql = "UPDATE dde_records SET submission_count = submission_count + 1 WHERE id = ?";
                jdbcTemplate.update(updateSql, record.get("id"));
                return ResponseEntity.ok("Verification complete");
            }
        } finally {
            if (previousStudy == null) {
                TenantContext.setCurrentStudy(null);
            } else {
                TenantContext.setCurrentStudy(previousStudy);
            }
        }
    }

    private boolean isStudyAuthorized(Integer requestedStudyId, HttpServletRequest request) {
        if (TenantContext.isBypass()) {
            return true;
        }

        Integer currentStudyContext = TenantContext.getCurrentStudy();
        if (currentStudyContext != null) {
            return currentStudyContext.equals(requestedStudyId);
        }

        if (request != null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                UserAccountBean userBean = (UserAccountBean) session.getAttribute("userBean");
                if (userBean != null) {
                    if (userBean.isSysAdmin()) {
                        return true;
                    }
                    if (userBean.getActiveStudyId() > 0) {
                        return userBean.getActiveStudyId() == requestedStudyId;
                    }
                    StudyUserRoleBean roleBean = userBean.getRoleByStudy(requestedStudyId);
                    return roleBean != null && roleBean.isActive();
                }
            }
        }

        return true;
    }
}
