package com.example.timerecorder.scheduler;

import com.example.timerecorder.config.TimeRecorderProperties;
import com.example.timerecorder.service.TimeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
@RequiredArgsConstructor
public class TimeRecordScheduler {

    private final TimeRecordService timeRecordService;
    private final TimeRecorderProperties properties;

    private ScheduledExecutorService recordingExecutor;
    private ScheduledExecutorService reconnectionExecutor;
    
    private final AtomicBoolean started = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (started.compareAndSet(false, true)) {
            startSchedulers();
        }
    }

    private void startSchedulers() {
        log.info("Starting time recording schedulers...");
        log.info("Record interval: {} ms, Reconnect interval: {} ms",
                properties.getRecordIntervalMs(),
                properties.getReconnectIntervalMs());

        recordingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "time-recorder");
            t.setDaemon(true);
            return t;
        });

        reconnectionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "db-reconnector");
            t.setDaemon(true);
            return t;
        });

        recordingExecutor.scheduleAtFixedRate(
                this::safeRecordTime,
                0,
                properties.getRecordIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        reconnectionExecutor.scheduleAtFixedRate(
                this::safeAttemptReconnection,
                properties.getReconnectIntervalMs(), // Initial delay
                properties.getReconnectIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        log.info("Schedulers started successfully");
    }

    private void safeRecordTime() {
        try {
            timeRecordService.recordCurrentTime();
        } catch (Exception e) {
            log.error("Unexpected error during time recording: {}", e.getMessage(), e);
        }
    }

    private void safeAttemptReconnection() {
        try {
            if (!timeRecordService.isDatabaseAvailable() ||
                timeRecordService.getBufferSize() > 0) {
                timeRecordService.attemptReconnection();
            }
        } catch (Exception e) {
            log.error("Unexpected error during reconnection attempt: {}", e.getMessage(), e);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        log.info("Shutting down schedulers...");
        
        if (recordingExecutor != null) {
            recordingExecutor.shutdown();
            try {
                if (!recordingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    recordingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                recordingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (reconnectionExecutor != null) {
            reconnectionExecutor.shutdown();
            try {
                if (!reconnectionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectionExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectionExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("Schedulers shut down");
    }
}
