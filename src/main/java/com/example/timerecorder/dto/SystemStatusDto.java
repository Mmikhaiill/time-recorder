package com.example.timerecorder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemStatusDto {

    private boolean databaseAvailable;

    private int bufferedRecordsCount;

    private int maxBufferCapacity;

    private double bufferUtilization;

    private long totalRecordsWritten;

    private long recordsDropped;

    private Instant lastSuccessfulWrite;

    private Instant databaseDownSince;

    private String outageDuration;

    private int reconnectionAttempts;

    private boolean recordingEnabled;

    private String uptime;

    private Instant timestamp;
}
