package com.example.timerecorder.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "time_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TimeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;


    @Column(name = "persisted_at", nullable = false)
    private Instant persistedAt;

    @Column(name = "was_buffered", nullable = false)
    private boolean wasBuffered;

    @PrePersist
    void prePersist() {
        if (persistedAt == null) {
            persistedAt = Instant.now();
        }
    }

    public static TimeRecord now() {
        return TimeRecord.builder()
                .recordedAt(Instant.now())
                .wasBuffered(false)
                .build();
    }

    public static TimeRecord at(Instant timestamp, boolean wasBuffered) {
        return TimeRecord.builder()
                .recordedAt(timestamp)
                .wasBuffered(wasBuffered)
                .build();
    }
}
