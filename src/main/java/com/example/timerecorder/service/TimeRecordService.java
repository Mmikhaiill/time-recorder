package com.example.timerecorder.service;

import com.example.timerecorder.config.TimeRecorderProperties;
import com.example.timerecorder.dto.SystemStatusDto;
import com.example.timerecorder.dto.TimeRecordDto;
import com.example.timerecorder.dto.TimeRecordsResponse;
import com.example.timerecorder.entity.TimeRecord;
import com.example.timerecorder.repository.TimeRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Slf4j
public class TimeRecordService {

    private final TimeRecordRepository repository;
    private final TimeRecorderProperties properties;
    private final MeterRegistry meterRegistry;

    private final ConcurrentLinkedQueue<Instant> recordBuffer = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final AtomicLong totalRecordsWritten = new AtomicLong(0);
    private final AtomicLong recordsDropped = new AtomicLong(0);
    private final AtomicInteger reconnectionAttempts = new AtomicInteger(0);
    
    private volatile Instant lastSuccessfulWrite = null;
    private volatile Instant databaseDownSince = null;
    private volatile Instant applicationStartTime;

    private final ReentrantLock flushLock = new ReentrantLock();

    private Counter recordsWrittenCounter;
    private Counter recordsBufferedCounter;
    private Counter recordsDroppedCounter;
    private Timer writeTimer;

    public TimeRecordService(TimeRecordRepository repository,
                             TimeRecorderProperties properties,
                             MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initialize() {
        applicationStartTime = Instant.now();
        initializeMetrics();
        log.info("TimeRecordService initialized with buffer size: {}, batch size: {}",
                properties.getMaxBufferSize(), properties.getBatchSize());
    }

    private void initializeMetrics() {
        recordsWrittenCounter = Counter.builder("timerecorder.records.written")
                .description("Total number of records written to database")
                .register(meterRegistry);

        recordsBufferedCounter = Counter.builder("timerecorder.records.buffered")
                .description("Total number of records added to buffer")
                .register(meterRegistry);

        recordsDroppedCounter = Counter.builder("timerecorder.records.dropped")
                .description("Total number of records dropped due to buffer overflow")
                .register(meterRegistry);

        writeTimer = Timer.builder("timerecorder.write.duration")
                .description("Time taken to write records to database")
                .register(meterRegistry);

        Gauge.builder("timerecorder.buffer.size", recordBuffer, ConcurrentLinkedQueue::size)
                .description("Current number of records in buffer")
                .register(meterRegistry);

        Gauge.builder("timerecorder.database.available", databaseAvailable, ab -> ab.get() ? 1.0 : 0.0)
                .description("Database availability (1=available, 0=unavailable)")
                .register(meterRegistry);
    }

    public void recordCurrentTime() {
        if (!properties.isEnabled()) {
            log.trace("Recording is disabled");
            return;
        }

        Instant now = Instant.now();

        if (databaseAvailable.get()) {
            if (!writeRecordToDatabase(now, false)) {
                addToBuffer(now);
            }
        } else {
            addToBuffer(now);
        }
    }

    private boolean writeRecordToDatabase(Instant timestamp, boolean wasBuffered) {
        try {
            Timer.Sample sample = Timer.start(meterRegistry);
            
            TimeRecord record = TimeRecord.at(timestamp, wasBuffered);
            repository.save(record);
            
            sample.stop(writeTimer);
            
            onWriteSuccess();
            recordsWrittenCounter.increment();
            totalRecordsWritten.incrementAndGet();
            
            log.trace("Recorded time: {} (buffered: {})", timestamp, wasBuffered);
            return true;
            
        } catch (Exception e) {
            onWriteFailure(e);
            return false;
        }
    }

    private void addToBuffer(Instant timestamp) {
        if (recordBuffer.size() >= properties.getMaxBufferSize()) {
            Instant dropped = recordBuffer.poll();
            if (dropped != null) {
                recordsDropped.incrementAndGet();
                recordsDroppedCounter.increment();
                log.warn("Buffer full ({} records). Dropped oldest record: {}",
                        properties.getMaxBufferSize(), dropped);
            }
        }

        recordBuffer.offer(timestamp);
        recordsBufferedCounter.increment();
        log.debug("Buffered timestamp: {}. Buffer size: {}", timestamp, recordBuffer.size());
    }

    private void onWriteSuccess() {
        lastSuccessfulWrite = Instant.now();
        
        if (!databaseAvailable.get()) {
            Duration outageDuration = Duration.between(databaseDownSince, Instant.now());
            log.info("Database connection RESTORED after {} seconds. Reconnection attempts: {}",
                    outageDuration.toSeconds(), reconnectionAttempts.get());
            
            databaseAvailable.set(true);
            databaseDownSince = null;
            reconnectionAttempts.set(0);
        }
    }

    private void onWriteFailure(Exception e) {
        if (databaseAvailable.get()) {
            databaseAvailable.set(false);
            databaseDownSince = Instant.now();
            log.error("Database connection LOST. Error: {}. " +
                      "Will buffer records and retry every {} ms",
                    e.getMessage(), properties.getReconnectIntervalMs());
        } else {
            log.debug("Database still unavailable: {}", e.getMessage());
        }
    }

    /**
     * Попытка переподключения
     */
    public void attemptReconnection() {
        if (databaseAvailable.get() && recordBuffer.isEmpty()) {
            return;
        }

        if (!flushLock.tryLock()) {
            log.debug("Flush already in progress, skipping this attempt");
            return;
        }

        try {
            reconnectionAttempts.incrementAndGet();
            log.info("Attempting database reconnection (attempt #{}). " +
                     "Buffer size: {} records",
                    reconnectionAttempts.get(), recordBuffer.size());

            if (!testDatabaseConnection()) {
                log.warn("Database still unavailable. Will retry in {} ms",
                        properties.getReconnectIntervalMs());
                return;
            }

            flushBuffer();

        } finally {
            flushLock.unlock();
        }
    }

    /**
     * Проверка соединения с БД
     */
    private boolean testDatabaseConnection() {
        try {
            repository.count();
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    @Transactional
    public void flushBuffer() {
        if (recordBuffer.isEmpty()) {
            log.debug("Buffer is empty, nothing to flush");
            return;
        }

        int totalBuffered = recordBuffer.size();
        log.info("Starting buffer flush. {} records to write", totalBuffered);

        Instant flushStartTime = Instant.now();
        int recordsWritten = 0;
        int batchCount = 0;
        List<TimeRecord> batch = new ArrayList<>(properties.getBatchSize());

        while (!recordBuffer.isEmpty()) {
            if (Duration.between(flushStartTime, Instant.now()).toMillis()
                    > properties.getBatchWriteTimeoutMs()) {
                log.warn("Flush timeout reached after {} records. " +
                         "{} records remaining in buffer",
                        recordsWritten, recordBuffer.size());
                break;
            }

            batch.clear();
            for (int i = 0; i < properties.getBatchSize() && !recordBuffer.isEmpty(); i++) {
                Instant timestamp = recordBuffer.poll();
                if (timestamp != null) {
                    batch.add(TimeRecord.at(timestamp, true));
                }
            }

            if (batch.isEmpty()) {
                break;
            }

            try {
                repository.saveAll(batch);
                recordsWritten += batch.size();
                batchCount++;
                
                recordsWrittenCounter.increment(batch.size());
                totalRecordsWritten.addAndGet(batch.size());
                
                log.debug("Written batch #{}: {} records", batchCount, batch.size());
                
            } catch (Exception e) {

                log.error("Batch write failed: {}. Re-buffering {} records",
                        e.getMessage(), batch.size());
                for (TimeRecord record : batch) {
                    recordBuffer.offer(record.getRecordedAt());
                }
                
                onWriteFailure(e);
                return;
            }
        }

        Duration flushDuration = Duration.between(flushStartTime, Instant.now());
        log.info("Buffer flush completed. Written {} records in {} batches. Duration: {} ms",
                recordsWritten, batchCount, flushDuration.toMillis());
        
        onWriteSuccess();
    }

    @Transactional(readOnly = true)
    public TimeRecordsResponse getTimeRecords(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<TimeRecord> recordPage = repository.findAllOrderByIdAsc(pageRequest);

        List<TimeRecordDto> dtos = recordPage.getContent().stream()
                .map(this::toDto)
                .toList();

        return TimeRecordsResponse.builder()
                .records(dtos)
                .totalCount(recordPage.getTotalElements())
                .bufferedCount(recordBuffer.size())
                .databaseStatus(databaseAvailable.get() ? "CONNECTED" : "DISCONNECTED")
                .generatedAt(Instant.now())
                .page(page)
                .size(size)
                .totalPages(recordPage.getTotalPages())
                .build();
    }

    @Transactional(readOnly = true)
    public TimeRecordsResponse getAllTimeRecords() {
        List<TimeRecord> records = repository.findAllOrderByIdAsc();

        List<TimeRecordDto> dtos = records.stream()
                .map(this::toDto)
                .toList();

        return TimeRecordsResponse.builder()
                .records(dtos)
                .totalCount((long) records.size())
                .bufferedCount(recordBuffer.size())
                .databaseStatus(databaseAvailable.get() ? "CONNECTED" : "DISCONNECTED")
                .generatedAt(Instant.now())
                .build();
    }

    public SystemStatusDto getSystemStatus() {
        double bufferUtilization = properties.getMaxBufferSize() > 0
                ? (double) recordBuffer.size() / properties.getMaxBufferSize() * 100
                : 0;

        String outageDuration = null;
        if (databaseDownSince != null) {
            Duration duration = Duration.between(databaseDownSince, Instant.now());
            outageDuration = formatDuration(duration);
        }

        Duration uptime = Duration.between(applicationStartTime, Instant.now());

        return SystemStatusDto.builder()
                .databaseAvailable(databaseAvailable.get())
                .bufferedRecordsCount(recordBuffer.size())
                .maxBufferCapacity(properties.getMaxBufferSize())
                .bufferUtilization(Math.round(bufferUtilization * 100.0) / 100.0)
                .totalRecordsWritten(totalRecordsWritten.get())
                .recordsDropped(recordsDropped.get())
                .lastSuccessfulWrite(lastSuccessfulWrite)
                .databaseDownSince(databaseDownSince)
                .outageDuration(outageDuration)
                .reconnectionAttempts(reconnectionAttempts.get())
                .recordingEnabled(properties.isEnabled())
                .uptime(formatDuration(uptime))
                .timestamp(Instant.now())
                .build();
    }

    public boolean isDatabaseAvailable() {
        return databaseAvailable.get();
    }


    public int getBufferSize() {
        return recordBuffer.size();
    }

    private TimeRecordDto toDto(TimeRecord record) {
        return TimeRecordDto.builder()
                .id(record.getId())
                .recordedAt(record.getRecordedAt())
                .persistedAt(record.getPersistedAt())
                .wasBuffered(record.isWasBuffered())
                .build();
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Попытка записать из буффера в БД перед отключением
     */
    @PreDestroy
    void shutdown() {
        log.info("Shutting down TimeRecordService. Buffer size: {}", recordBuffer.size());
        
        if (!recordBuffer.isEmpty() && databaseAvailable.get()) {
            try {
                log.info("Attempting to flush {} buffered records before shutdown...",
                        recordBuffer.size());
                flushBuffer();
            } catch (Exception e) {
                log.error("Failed to flush buffer during shutdown: {}. " +
                          "{} records will be lost",
                        e.getMessage(), recordBuffer.size());
            }
        }
    }
}
