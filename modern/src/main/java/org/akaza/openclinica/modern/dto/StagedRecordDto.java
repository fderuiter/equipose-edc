package org.akaza.openclinica.modern.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class StagedRecordDto {

    @JsonProperty("record_id")
    private String recordId;

    @JsonProperty("target_study")
    private String targetStudy;

    @JsonProperty("target_form_version")
    private String targetFormVersion;

    public StagedRecordDto() {}

    public StagedRecordDto(String recordId, String targetStudy, String targetFormVersion) {
        this.recordId = recordId;
        this.targetStudy = targetStudy;
        this.targetFormVersion = targetFormVersion;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public String getTargetStudy() {
        return targetStudy;
    }

    public void setTargetStudy(String targetStudy) {
        this.targetStudy = targetStudy;
    }

    public String getTargetFormVersion() {
        return targetFormVersion;
    }

    public void setTargetFormVersion(String targetFormVersion) {
        this.targetFormVersion = targetFormVersion;
    }
}
