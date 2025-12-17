package com.example.timerecorder.controller;

import com.example.timerecorder.dto.SystemStatusDto;
import com.example.timerecorder.dto.TimeRecordsResponse;
import com.example.timerecorder.service.TimeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collections;

@Controller
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class TimeRecordController {

    private final TimeRecordService timeRecordService;

    @GetMapping(value = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TimeRecordsResponse getRecords(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        log.debug("GET /records: page={}, size={}", page, size);
        int limitedSize = Math.min(size, 1000);

        try {
            return timeRecordService.getTimeRecords(page, limitedSize);
        } catch (Exception e) {
            log.warn("Failed to fetch records: {}", e.getMessage());
            return emptyResponse(page, limitedSize);
        }
    }

    @GetMapping(value = "/records/all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TimeRecordsResponse getAllRecords() {
        try {
            return timeRecordService.getAllTimeRecords();
        } catch (Exception e) {
            log.warn("Failed to fetch all records: {}", e.getMessage());
            return emptyResponse(0, 0);
        }
    }

    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SystemStatusDto getStatus() {
        return timeRecordService.getSystemStatus();
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public String health() {
        boolean dbUp = timeRecordService.isDatabaseAvailable();
        int buffered = timeRecordService.getBufferSize();
        return "{\"status\":\"UP\",\"database\":\"" + (dbUp ? "CONNECTED" : "DISCONNECTED") +
                "\",\"bufferedRecords\":" + buffered + "}";
    }

    @GetMapping("/records/html")
    public String getRecordsHtml(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            Model model) {

        log.debug("GET /records/html: page={}, size={}", page, size);
        int limitedSize = Math.min(size, 1000);

        TimeRecordsResponse data;
        try {
            data = timeRecordService.getTimeRecords(page, limitedSize);
        } catch (Exception e) {
            log.warn("Failed to fetch records: {}", e.getMessage());
            data = emptyResponse(page, limitedSize);
        }

        model.addAttribute("records", data.getRecords());
        model.addAttribute("totalCount", data.getTotalCount());
        model.addAttribute("bufferedCount", data.getBufferedCount());
        model.addAttribute("dbStatus", data.getDatabaseStatus());
        model.addAttribute("connected", "CONNECTED".equals(data.getDatabaseStatus()));
        model.addAttribute("page", data.getPage());
        model.addAttribute("size", data.getSize());
        model.addAttribute("totalPages", data.getTotalPages());

        return "records";
    }

    @GetMapping("/status/html")
    public String getStatusHtml(Model model) {
        SystemStatusDto status = timeRecordService.getSystemStatus();
        model.addAttribute("status", status);
        return "status";
    }

    private TimeRecordsResponse emptyResponse(int page, int size) {
        return TimeRecordsResponse.builder()
                .records(Collections.emptyList())
                .totalCount(0L)
                .bufferedCount(timeRecordService.getBufferSize())
                .databaseStatus("DISCONNECTED")
                .generatedAt(Instant.now())
                .page(page)
                .size(size)
                .totalPages(0)
                .build();
    }
}