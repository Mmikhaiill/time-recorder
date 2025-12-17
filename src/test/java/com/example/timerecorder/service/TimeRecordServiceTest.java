package com.example.timerecorder.service;

import com.example.timerecorder.config.TimeRecorderProperties;
import com.example.timerecorder.dto.SystemStatusDto;
import com.example.timerecorder.dto.TimeRecordsResponse;
import com.example.timerecorder.entity.TimeRecord;
import com.example.timerecorder.repository.TimeRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimeRecordService Unit Tests")
class TimeRecordServiceTest {

    @Mock
    private TimeRecordRepository repository;

    private TimeRecorderProperties properties;
    private MeterRegistry meterRegistry;
    private TimeRecordService service;

    @BeforeEach
    void setUp() {
        properties = new TimeRecorderProperties();
        properties.setEnabled(true);
        properties.setMaxBufferSize(100);
        properties.setBatchSize(10);
        properties.setBatchWriteTimeoutMs(5000);

        meterRegistry = new SimpleMeterRegistry();
        
        service = new TimeRecordService(repository, properties, meterRegistry);
        service.initialize();
    }

    @Test
    @DisplayName("Should record current time successfully when DB is available")
    void shouldRecordCurrentTimeWhenDbAvailable() {
        // Given
        when(repository.save(any(TimeRecord.class))).thenAnswer(invocation -> {
            TimeRecord record = invocation.getArgument(0);
            record.setId(1L);
            return record;
        });

        // When
        service.recordCurrentTime();

        // Then
        ArgumentCaptor<TimeRecord> captor = ArgumentCaptor.forClass(TimeRecord.class);
        verify(repository).save(captor.capture());
        
        TimeRecord saved = captor.getValue();
        assertThat(saved.getRecordedAt()).isNotNull();
        assertThat(saved.isWasBuffered()).isFalse();
        assertThat(service.isDatabaseAvailable()).isTrue();
    }

    @Test
    @DisplayName("Should buffer records when DB is unavailable")
    void shouldBufferRecordsWhenDbUnavailable() {
        // Given
        when(repository.save(any(TimeRecord.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // When
        service.recordCurrentTime();
        service.recordCurrentTime();
        service.recordCurrentTime();

        // Then
        assertThat(service.isDatabaseAvailable()).isFalse();
        assertThat(service.getBufferSize()).isEqualTo(3);
        
        verify(repository, atLeast(1)).save(any(TimeRecord.class));
    }

    @Test
    @DisplayName("Should drop oldest records when buffer is full")
    void shouldDropOldestWhenBufferFull() {
        // Given
        properties.setMaxBufferSize(3);
        when(repository.save(any(TimeRecord.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        // When
        for (int i = 0; i < 5; i++) {
            service.recordCurrentTime();
        }

        // Then
        assertThat(service.getBufferSize()).isEqualTo(3);
        
        SystemStatusDto status = service.getSystemStatus();
        assertThat(status.getRecordsDropped()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Should not record when disabled")
    void shouldNotRecordWhenDisabled() {
        // Given
        properties.setEnabled(false);

        // When
        service.recordCurrentTime();

        // Then
        verify(repository, never()).save(any());
        assertThat(service.getBufferSize()).isZero();
    }

    @Test
    @DisplayName("Should flush buffer successfully on reconnection")
    void shouldFlushBufferOnReconnection() {
        // Given
        when(repository.save(any(TimeRecord.class)))
                .thenThrow(new RuntimeException("Connection refused"))
                .thenThrow(new RuntimeException("Connection refused"))
                .thenAnswer(invocation -> {
                    TimeRecord record = invocation.getArgument(0);
                    record.setId(1L);
                    return record;
                });

        service.recordCurrentTime();
        service.recordCurrentTime();
        
        when(repository.count()).thenReturn(0L);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> {
            List<TimeRecord> records = invocation.getArgument(0);
            for (int i = 0; i < records.size(); i++) {
                records.get(i).setId((long) i + 1);
            }
            return records;
        });

        // When
        service.attemptReconnection();

        // Then
        assertThat(service.isDatabaseAvailable()).isTrue();
        assertThat(service.getBufferSize()).isZero();
        verify(repository).saveAll(anyList());
    }

    @Test
    @DisplayName("Should return records in chronological order")
    void shouldReturnRecordsInChronologicalOrder() {
        // Given
        List<TimeRecord> records = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            TimeRecord record = TimeRecord.builder()
                    .id(i)
                    .recordedAt(Instant.now().minusSeconds(4 - i))
                    .wasBuffered(false)
                    .build();
            records.add(record);
        }

        when(repository.findAllOrderByIdAsc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(records));

        // When
        TimeRecordsResponse response = service.getTimeRecords(0, 100);

        // Then
        assertThat(response.getRecords()).hasSize(3);
        assertThat(response.getRecords().get(0).getId()).isEqualTo(1L);
        assertThat(response.getRecords().get(1).getId()).isEqualTo(2L);
        assertThat(response.getRecords().get(2).getId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should provide accurate system status")
    void shouldProvideAccurateSystemStatus() {
        // When
        SystemStatusDto status = service.getSystemStatus();

        // Then
        assertThat(status.isDatabaseAvailable()).isTrue();
        assertThat(status.getBufferedRecordsCount()).isZero();
        assertThat(status.getMaxBufferCapacity()).isEqualTo(100);
        assertThat(status.isRecordingEnabled()).isTrue();
        assertThat(status.getUptime()).isNotNull();
        assertThat(status.getTimestamp()).isNotNull();
    }
}
