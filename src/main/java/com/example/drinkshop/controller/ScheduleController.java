package com.example.drinkshop.controller;

import com.example.drinkshop.dto.ScheduleDtos.*;
import com.example.drinkshop.service.ScheduleService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 排班 API。查詢所有員工可用（員工只看得到已發布的週）；其餘操作限店長或總部。
 * date 參數可以是該週任何一天，後端自動換算成週一。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {
    private final ScheduleService service;
    public ScheduleController(ScheduleService service) { this.service = service; }

    /** GET /api/schedule/templates?includeInactive=true */
    @GetMapping("/templates")
    List<TemplateResponse> templates(@RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return service.listTemplates(includeInactive);
    }

    /** POST /api/schedule/templates { name, startTime, endTime } */
    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    TemplateResponse createTemplate(@Valid @RequestBody CreateTemplate request, Principal principal) {
        return service.createTemplate(request, principal.getName());
    }

    /** PUT /api/schedule/templates/{id} { name, startTime, endTime, active } */
    @PutMapping("/templates/{id}")
    TemplateResponse updateTemplate(@PathVariable("id") long id, @Valid @RequestBody UpdateTemplate request, Principal principal) {
        return service.updateTemplate(id, request, principal.getName());
    }

    /** GET /api/schedule/week?date=YYYY-MM-DD（不填為本週） */
    @GetMapping("/week")
    WeekResponse week(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      Principal principal) {
        return service.week(date, principal.getName());
    }

    /** POST /api/schedule/entries { workDate, staffId, templateId?, startTime?, endTime?, note? } */
    @PostMapping("/entries")
    @ResponseStatus(HttpStatus.CREATED)
    SaveResult create(@Valid @RequestBody SaveEntry request, Principal principal) {
        return service.create(request, principal.getName());
    }

    /** PUT /api/schedule/entries/{id} */
    @PutMapping("/entries/{id}")
    SaveResult update(@PathVariable("id") long id, @Valid @RequestBody SaveEntry request, Principal principal) {
        return service.update(id, request, principal.getName());
    }

    /** DELETE /api/schedule/entries/{id} */
    @DeleteMapping("/entries/{id}")
    Map<String, Object> delete(@PathVariable("id") long id, Principal principal) {
        service.delete(id, principal.getName());
        return Map.of("success", true);
    }

    /** POST /api/schedule/week/publish?date=YYYY-MM-DD */
    @PostMapping("/week/publish")
    WeekResponse publish(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         Principal principal) {
        return service.publish(date, principal.getName());
    }

    /** POST /api/schedule/week/copy-previous?date=YYYY-MM-DD */
    @PostMapping("/week/copy-previous")
    WeekResponse copyPrevious(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                              Principal principal) {
        return service.copyPreviousWeek(date, principal.getName());
    }
}
