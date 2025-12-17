package com.example.timerecorder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TimeRecordsResponse {

    private List<TimeRecordDto> records;

    private Long totalCount;

    private Integer bufferedCount;

    private String databaseStatus;

    private Instant generatedAt;

    private Integer page;

    private Integer size;

    private Integer totalPages;
}
