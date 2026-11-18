package com.ganzi.emori.record;

import com.ganzi.emori.api.DbSupport.Page;
import com.ganzi.emori.record.RecordModels.*;
import com.ganzi.emori.record.RecordModels.Record;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records")
public class RecordController {
    private final RecordService records;
    public RecordController(RecordService records) { this.records = records; }
    @GetMapping
    public Page<Record> list(HttpServletRequest request, @RequestParam(required = false) String side,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String date, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return records.list((UUID) request.getAttribute("userId"), side, from, to, date, cursor, limit);
    }
    @GetMapping("/{id}")
    public RecordDetail detail(HttpServletRequest request, @PathVariable String id) {
        return records.detail((UUID) request.getAttribute("userId"), id);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable String id) {
        records.delete((UUID) request.getAttribute("userId"), id);
        return ResponseEntity.noContent().build();
    }
}
