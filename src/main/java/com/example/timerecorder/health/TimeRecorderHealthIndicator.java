package com.example.timerecorder.health;

import com.example.timerecorder.service.TimeRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("timeRecorder")
@RequiredArgsConstructor
public class TimeRecorderHealthIndicator implements HealthIndicator {

    private final TimeRecordService timeRecordService;

    @Override
    public Health health() {
        boolean dbAvailable = timeRecordService.isDatabaseAvailable();
        int bufferSize = timeRecordService.getBufferSize();
        
        Health.Builder builder = dbAvailable 
                ? Health.up() 
                : Health.down();
        
        builder.withDetail("databaseConnected", dbAvailable)
               .withDetail("bufferedRecords", bufferSize);
        
        if (bufferSize > 50000) {
            builder.withDetail("warning", "Buffer is more than 50% full");
        }
        
        if (!dbAvailable && bufferSize < 100000) {
            builder.status("DEGRADED");
        }
        
        return builder.build();
    }
}
