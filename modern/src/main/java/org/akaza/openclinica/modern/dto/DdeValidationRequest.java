package org.akaza.openclinica.modern.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class DdeValidationRequest {

    @NotNull(message = "studyId is required")
    @JsonProperty("studyId")
    @JsonAlias({"study_id", "studyId"})
    private Integer studyId;

    @NotBlank(message = "subjectOid is required")
    private String subjectOid;

    @NotBlank(message = "itemOid is required")
    private String itemOid;

    @NotBlank(message = "value is required")
    private String value;

    private String override;

    public Integer getStudyId() {
        return studyId;
    }

    public void setStudyId(Integer studyId) {
        this.studyId = studyId;
    }

    public String getSubjectOid() {
        return subjectOid;
    }

    public void setSubjectOid(String subjectOid) {
        this.subjectOid = subjectOid;
    }

    public String getItemOid() {
        return itemOid;
    }

    public void setItemOid(String itemOid) {
        this.itemOid = itemOid;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getOverride() {
        return override;
    }

    public void setOverride(String override) {
        this.override = override;
    }
}
