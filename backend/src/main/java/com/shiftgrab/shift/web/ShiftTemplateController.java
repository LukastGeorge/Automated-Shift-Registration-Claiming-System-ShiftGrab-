package com.shiftgrab.shift.web;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shiftgrab.shift.internal.ShiftTemplate;
import com.shiftgrab.shift.internal.ShiftTemplateService;
import com.shiftgrab.shift.internal.ShiftTemplateService.GenerationResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/shift-templates")
@PreAuthorize("hasRole('MANAGER')")
@Tag(name = "Shift templates")
class ShiftTemplateController {

    private final ShiftTemplateService service;

    ShiftTemplateController(ShiftTemplateService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a recurring shift template (SG-23)",
            description = "Times are local to the organisation time zone; endTime <= startTime means an overnight "
                    + "shift. openBefore is an ISO-8601 duration, e.g. P7D.")
    ResponseEntity<TemplateResponse> create(@Valid @RequestBody CreateTemplateRequest r) {
        ShiftTemplate template = service.create(r.name(), r.weekdays(), r.startTime(), r.endTime(), r.maxSlots(),
                r.openBefore(), r.validFrom(), r.validTo());
        return ResponseEntity.created(URI.create("/api/v1/shift-templates/" + template.id()))
                .body(TemplateResponse.from(template));
    }

    @GetMapping
    @Operation(summary = "List shift templates")
    List<TemplateResponse> list() {
        return service.list().stream().map(TemplateResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one shift template")
    TemplateResponse get(@PathVariable long id) {
        return TemplateResponse.from(service.get(id));
    }

    @PostMapping("/{id}/generate")
    @Operation(summary = "Generate the template's shifts (SG-23)",
            description = "All-or-nothing. Safe to repeat: existing and past occurrences are skipped and counted.")
    GenerationResult generate(@PathVariable long id) {
        return service.generate(id);
    }

    record CreateTemplateRequest(
            @NotBlank @Size(max = 100)
            @Pattern(regexp = ShiftController.NO_CONTROL_CHARACTERS,
                    message = "must not contain line breaks or control characters")
            String name,
            @NotEmpty @Schema(description = "ISO weekdays, 1 = Monday ... 7 = Sunday", example = "[1,2,3,4,5]")
            Set<Integer> weekdays,
            @NotNull @Schema(type = "string", example = "08:00") LocalTime startTime,
            @NotNull @Schema(type = "string", example = "12:00") LocalTime endTime,
            @NotNull @Min(1) @Max(500) Integer maxSlots,
            @NotNull @Schema(type = "string", example = "P7D") Duration openBefore,
            @NotNull LocalDate validFrom,
            @NotNull LocalDate validTo) {
    }

    record TemplateResponse(long id, String name, List<Integer> weekdays, LocalTime startTime, LocalTime endTime,
            boolean overnight, int maxSlots, String openBefore, LocalDate validFrom, LocalDate validTo) {

        static TemplateResponse from(ShiftTemplate t) {
            return new TemplateResponse(t.id(), t.name(), t.weekdays().stream().map(DayOfWeek::getValue).toList(),
                    t.startTime(), t.endTime(), t.isOvernight(), t.maxSlots(), t.openBefore().toString(),
                    t.validFrom(), t.validTo());
        }
    }
}
