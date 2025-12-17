package com.example.timerecorder.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@ConfigurationProperties(prefix = "app.time-recorder")
@Validated
@Getter
@Setter
public class TimeRecorderProperties {

    @Positive
    private long recordIntervalMs = 1000;

    @Positive
    private long reconnectIntervalMs = 5000;

    @Positive
    @Max(1_000_000)
    private int maxBufferSize = 100_000;

    @Min(1)
    @Max(1000)
    private int batchSize = 100;

    @Positive
    private long batchWriteTimeoutMs = 30_000;

    private boolean enabled = true;
}
