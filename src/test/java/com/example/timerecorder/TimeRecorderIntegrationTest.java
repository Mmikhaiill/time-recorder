package com.example.timerecorder;

import com.example.timerecorder.config.TimeRecorderProperties;
import com.example.timerecorder.dto.TimeRecordsResponse;
import com.example.timerecorder.entity.TimeRecord;
import com.example.timerecorder.repository.TimeRecordRepository;
import com.example.timerecorder.service.TimeRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Integration Tests")
class TimeRecorderIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TimeRecordRepository repository;

    @Autowired
    private TimeRecordService timeRecordService;

    @Autowired
    private TimeRecorderProperties properties;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        repository.deleteAll();
    }

    @Test
    @DisplayName("Should return empty list when no records exist")
    void shouldReturnEmptyListWhenNoRecords() {
        // When
        ResponseEntity<TimeRecordsResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/records",
                TimeRecordsResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRecords()).isEmpty();
        assertThat(response.getBody().getTotalCount()).isZero();
    }

    @Test
    @DisplayName("Should record and retrieve time records in order")
    void shouldRecordAndRetrieveInOrder() {
        // Given
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            TimeRecord record = TimeRecord.at(now.plusSeconds(i), false);
            repository.save(record);
        }

        // When
        ResponseEntity<TimeRecordsResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/records",
                TimeRecordsResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        
        List<Long> ids = response.getBody().getRecords().stream()
                .map(r -> r.getId())
                .toList();
        
        assertThat(ids).isSorted();
        assertThat(response.getBody().getTotalCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should return HTML format when requested")
    void shouldReturnHtmlFormat() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/api/records/html",
                String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<!DOCTYPE html>");
        assertThat(response.getBody()).contains("Записи времени");
    }

    @Test
    @DisplayName("Should return system status")
    void shouldReturnSystemStatus() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/api/status",
                String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("databaseAvailable");
        assertThat(response.getBody()).contains("bufferedRecordsCount");
    }

    @Test
    @DisplayName("Should paginate results correctly")
    void shouldPaginateResults() {
        // Given
        Instant now = Instant.now();
        for (int i = 0; i < 25; i++) {
            TimeRecord record = TimeRecord.at(now.plusSeconds(i), false);
            repository.save(record);
        }

        // When
        ResponseEntity<TimeRecordsResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/records?page=0&size=10",
                TimeRecordsResponse.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRecords()).hasSize(10);
        assertThat(response.getBody().getTotalCount()).isEqualTo(25);
        assertThat(response.getBody().getTotalPages()).isEqualTo(3);
        assertThat(response.getBody().getPage()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should record time when service method is called directly")
    void shouldRecordTimeWhenServiceCalled() {
        // Given
        long initialCount = repository.count();

        boolean wasEnabled = properties.isEnabled();
        properties.setEnabled(true);

        try {
            // When
            timeRecordService.recordCurrentTime();
            timeRecordService.recordCurrentTime();
            timeRecordService.recordCurrentTime();

            // Then
            long newCount = repository.count();
            assertThat(newCount).isEqualTo(initialCount + 3);
        } finally {
            properties.setEnabled(wasEnabled);
        }
    }

    @Test
    @DisplayName("Health endpoint should return status")
    void healthEndpointShouldReturnStatus() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/api/health",
                String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("status");
        assertThat(response.getBody()).contains("UP");
    }

    @Test
    @DisplayName("Actuator health should be available")
    void actuatorHealthShouldBeAvailable() {
        // When
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/actuator/health",
                String.class
        );

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
