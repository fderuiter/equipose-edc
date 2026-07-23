package org.akaza.openclinica.modern.dto;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MappingDataRequest {

    @NotBlank(message = "subject_id is required")
    @JsonProperty("subject_id")
    private String subjectId;

    @NotBlank(message = "event_id is required")
    @JsonProperty("event_id")
    private String eventId;

    @NotBlank(message = "item_value is required")
    @JsonProperty("item_value")
    private String itemValue;

    @JsonProperty("target_study")
    private String targetStudy;

    @JsonProperty("target_form_version")
    private String targetFormVersion;

    @JsonProperty("target_field")
    private String targetField;

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getItemValue() {
        return itemValue;
    }

    public void setItemValue(String itemValue) {
        this.itemValue = itemValue;
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

    public String getTargetField() {
        return targetField;
    }

    public void setTargetField(String targetField) {
        this.targetField = targetField;
    }
}
