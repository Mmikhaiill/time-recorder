package com.example.timerecorder.repository;

import com.example.timerecorder.entity.TimeRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimeRecordRepository extends JpaRepository<TimeRecord, Long> {


    @Query("SELECT t FROM TimeRecord t ORDER BY t.id ASC")
    List<TimeRecord> findAllOrderByIdAsc();

    @Query("SELECT t FROM TimeRecord t ORDER BY t.id ASC")
    Page<TimeRecord> findAllOrderByIdAsc(Pageable pageable);


    long countByWasBufferedTrue();


    @Query("SELECT t FROM TimeRecord t ORDER BY t.id DESC LIMIT 1")
    TimeRecord findLatestRecord();
}
