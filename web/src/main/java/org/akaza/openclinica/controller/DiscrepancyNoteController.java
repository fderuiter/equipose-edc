package org.akaza.openclinica.controller;

import org.akaza.openclinica.bean.core.DiscrepancyNoteType;
import org.akaza.openclinica.bean.core.ResolutionStatus;
import org.akaza.openclinica.bean.core.Role;
import org.akaza.openclinica.bean.core.Status;
import org.akaza.openclinica.bean.core.UserType;
import org.akaza.openclinica.bean.login.StudyUserRoleBean;
import org.akaza.openclinica.bean.login.UserAccountBean;
import org.akaza.openclinica.bean.login.UserDTO;
import org.akaza.openclinica.bean.managestudy.DiscrepancyNoteBean;
import org.akaza.openclinica.bean.managestudy.EventDefinitionCRFBean;
import org.akaza.openclinica.bean.managestudy.StudyBean;
import org.akaza.openclinica.bean.managestudy.StudyEventBean;
import org.akaza.openclinica.bean.managestudy.StudyEventDefinitionBean;
import org.akaza.openclinica.bean.managestudy.StudySubjectBean;
import org.akaza.openclinica.bean.service.StudyParameterValueBean;
import org.akaza.openclinica.bean.submit.CRFVersionBean;
import org.akaza.openclinica.control.SpringServletAccess;
import org.akaza.openclinica.dao.hibernate.AuthoritiesDao;
import org.akaza.openclinica.dao.login.UserAccountDAO;
import org.akaza.openclinica.dao.managestudy.DiscrepancyNoteDAO;
import org.akaza.openclinica.dao.managestudy.EventDefinitionCRFDAO;
import org.akaza.openclinica.dao.managestudy.StudyDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDAO;
import org.akaza.openclinica.dao.managestudy.StudyEventDefinitionDAO;
import org.akaza.openclinica.dao.managestudy.StudySubjectDAO;
import org.akaza.openclinica.dao.service.StudyParameterValueDAO;
import org.akaza.openclinica.dao.submit.CRFVersionDAO;
import org.akaza.openclinica.domain.user.AuthoritiesBean;
import org.akaza.openclinica.i18n.util.ResourceBundleProvider;
import org.akaza.openclinica.service.pmanage.ParticipantPortalRegistrar;
import org.akaza.openclinica.web.pform.PFormCache;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

@Controller
@RequestMapping(value = "/auth/api/v1/discrepancynote")
@ResponseStatus(value = org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
public class DiscrepancyNoteController {

	@Autowired
	private org.akaza.openclinica.service.managestudy.DiscrepancyNoteService discrepancyNoteService;

	@Autowired
	ServletContext context;

	@Autowired
	@Qualifier("userAccountDao")
	private UserAccountDAO udao;

	@Autowired
	@Qualifier("studySubjectDaoJDBC")
	private StudySubjectDAO ssdao;

	@Autowired
	@Qualifier("studyEventDefinitionDaoJDBC")
	private StudyEventDefinitionDAO seddao;

	@Autowired
	@Qualifier("studyeventdaojdbc")
	private StudyEventDAO sedao;

	@Autowired
	@Qualifier("studyDaoJDBC")
	private StudyDAO sdao;

	public static final String FORM_CONTEXT = "ecid";

	protected final Logger logger = LoggerFactory.getLogger(getClass().getName());

	@RequestMapping(value = "/dnote", method = RequestMethod.POST)
	public ResponseEntity buidDiscrepancyNote(@RequestBody HashMap<String, String> map, HttpServletRequest request) throws Exception {
		ResourceBundleProvider.updateLocale(new Locale("en_US"));
		System.out.println("I'm in EnketoForm DN Rest Method");
		org.springframework.http.HttpStatus httpStatus = null;

		String se_oid = map.get("EntityID");
		String ordinal = map.get("Ordinal");
		String entityName = map.get("EntityName"); // start_date , end_date , location
		String studySubjectOid = map.get("SS_OID");
		String noteType = map.get("NoteType");
		String resolutionStatus = map.get("Status");
		String assignedUser = map.get("AssignedUser");
		String owner = map.get("Owner");
		String description = map.get("Description");
		String detailedNotes = map.get("DetailedNote");
		String dn_id = map.get("DN_Id");
		dn_id = dn_id != null ? dn_id.replaceFirst("DN_",""): dn_id;

		UserAccountBean assignedUserBean = (UserAccountBean) udao.findByUserName(assignedUser);
		
		UserAccountBean ownerBean = null;
		if (owner != null && !owner.trim().isEmpty()) {
			ownerBean = (UserAccountBean) udao.findByUserName(owner);
		}
		if (ownerBean == null && request.getSession(false) != null) {
			ownerBean = (UserAccountBean) request.getSession(false).getAttribute("userBean");
		}

		StudySubjectBean ssBean = ssdao.findByOid(studySubjectOid);
		StudyEventDefinitionBean sedBean = seddao.findByOid(se_oid);
		StudyBean studyBean = getStudy(sedBean.getStudyId());
		StudyEventBean seBean = (StudyEventBean) sedao.findByStudySubjectIdAndDefinitionIdAndOrdinal(ssBean.getId(), sedBean.getId(), Integer.valueOf(ordinal));
		String entityType = "studyEvent";

		DiscrepancyNoteBean parent = (DiscrepancyNoteBean) discrepancyNoteService.findByPK(Integer.valueOf(dn_id));
		
		if (!mayProceed(resolutionStatus, noteType, seBean, entityName, parent, ownerBean)) {
			httpStatus = org.springframework.http.HttpStatus.BAD_REQUEST;
			return new ResponseEntity(httpStatus);
		}

		if (parent == null || !parent.isActive()){
			DiscrepancyNoteBean dnb = buildNote(description, detailedNotes, seBean.getId(), entityType, studyBean, ownerBean, assignedUserBean, 0, resolutionStatus, noteType, entityName);
			discrepancyNoteService.create(dnb);
			httpStatus = org.springframework.http.HttpStatus.OK;
		} else {
			DiscrepancyNoteBean dnb = buildNote(description, detailedNotes, seBean.getId(), entityType, studyBean, ownerBean, assignedUserBean, parent.getId(), resolutionStatus, noteType, entityName);
			discrepancyNoteService.create(dnb);
			httpStatus = org.springframework.http.HttpStatus.OK;
		}
		return new ResponseEntity(httpStatus);
	}

	private DiscrepancyNoteBean buildNote(String description, String detailedNotes, int entityId, String entityType, StudyBean sb, UserAccountBean ownerBean,
			UserAccountBean assignedUserBean, Integer parentId, String resolutionStatus, String noteType, String entityName) {
		DiscrepancyNoteBean dnb = new DiscrepancyNoteBean();

		dnb.setResStatus(ResolutionStatus.getByName(resolutionStatus));

		dnb.setEntityId(entityId);
		dnb.setStudyId(sb.getId());
		dnb.setEntityType(entityType);
		dnb.setDescription(description);
		dnb.setDetailedNotes(detailedNotes);
		dnb.setColumn(entityName);
		dnb.setOwner(ownerBean);
		if (assignedUserBean != null) {
			dnb.setAssignedUser(assignedUserBean);
			dnb.setAssignedUserId(assignedUserBean.getId());
		}
		dnb.setDisType(DiscrepancyNoteType.getByName(noteType));
		if (dnb.getDisType() != null) {
			dnb.setDiscrepancyNoteTypeId(dnb.getDisType().getId());
		}
		if (dnb.getResStatus() != null) {
			dnb.setResolutionStatusId(dnb.getResStatus().getId());
		}

		if (parentId != null) {
			dnb.setParentDnId(parentId);
		} else {
			dnb.setParentDnId(0);
		}
		return dnb;
	}

	private StudyBean getParentStudy(Integer studyId) {
		StudyBean study = getStudy(studyId);
		if (study.getParentStudyId() == 0) {
			return study;
		} else {
			StudyBean parentStudy = (StudyBean) sdao.findByPK(study.getParentStudyId());
			return parentStudy;
		}

	}

	private StudyBean getParentStudy(String studyOid) {
		StudyBean study = getStudy(studyOid);
		if (study.getParentStudyId() == 0) {
			return study;
		} else {
			StudyBean parentStudy = (StudyBean) sdao.findByPK(study.getParentStudyId());
			return parentStudy;
		}

	}

	private StudyBean getStudy(Integer id) {
		StudyBean studyBean = (StudyBean) sdao.findByPK(id);
		return studyBean;
	}

	private StudyBean getStudy(String oid) {
		StudyBean studyBean = (StudyBean) sdao.findByOid(oid);
		return studyBean;
	}

	private Date getDate(String dateInString) throws ParseException {
		SimpleDateFormat formatter = new SimpleDateFormat("dd-MMM-yyyy");
		Date date = formatter.parse(dateInString);

		System.out.println(date);
		System.out.println(formatter.format(date));

		return date;

	}

	public Boolean mayProceed(String resolutionStatus, String noteType, StudyEventBean seBean, String entityName, DiscrepancyNoteBean parent, UserAccountBean ownerBean) {
		Boolean result = true;
		if (!resolutionStatus.equals("Updated") && !resolutionStatus.equals("Resolution Proposed") && !resolutionStatus.equals("Closed") && !resolutionStatus.equals("New")) {
			result = false;
		}
		if (!noteType.equals("Annotation") && !noteType.equals("Query") && !noteType.equals("Reason for Change") && !noteType.equals("Failed Validation Check")) {
			result = false;
		}
		if (!seBean.isActive()) {
			result = false;
		}
		if (!entityName.equals("start_date") && !entityName.equals("end_date") && !entityName.equals("location")) {
			result = false;
		}
		if (ownerBean == null || !ownerBean.isActive()) {
			result = false;
		}

		return result;
	}

}
