package com.octopyder.payroll.org;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.dto.HolidayDto;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class HolidayService {

    private final HolidayRepository repository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public HolidayService(
        HolidayRepository repository,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    public List<HolidayDto> list(Integer year) {
        if (year != null) {
            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year, 12, 31);
            return repository.findByHolidayDateBetweenOrderByHolidayDateAsc(start, end)
                .stream().map(this::toDto).toList();
        }
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public HolidayDto create(HolidayDto.CreateRequest request) {
        if (request.holidayDate() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "Holiday date is required");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Holiday name is required");
        }
        if (request.name().length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Holiday name length cannot exceed 100 characters");
        }
        if (repository.existsById(request.holidayDate())) {
            throw new AppException(HttpStatus.CONFLICT, "HOLIDAY_ALREADY_EXISTS", "Holiday on " + request.holidayDate() + " already exists");
        }

        Holiday holiday = new Holiday(request.holidayDate(), request.name().trim());
        holiday.setCreatedAt(businessClock.instant());

        try {
            Holiday saved = repository.saveAndFlush(holiday);
            auditService.log("CREATE_HOLIDAY", "HOLIDAY", saved.getHolidayDate().toString(), null, "{\"name\":\"" + saved.getName() + "\"}");
            return toDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(HttpStatus.CONFLICT, "HOLIDAY_ALREADY_EXISTS", "Holiday on " + request.holidayDate() + " already exists");
        }
    }

    @Transactional
    public void delete(LocalDate date) {
        Holiday holiday = repository.findById(date)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Holiday not found"));

        repository.delete(holiday);
        auditService.log("DELETE_HOLIDAY", "HOLIDAY", date.toString(), null, "Holiday deleted: " + holiday.getName());
    }

    private HolidayDto toDto(Holiday h) {
        return new HolidayDto(h.getHolidayDate(), h.getName(), h.getCreatedAt());
    }
}
