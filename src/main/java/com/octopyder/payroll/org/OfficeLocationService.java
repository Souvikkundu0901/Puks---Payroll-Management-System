package com.octopyder.payroll.org;

import com.octopyder.payroll.audit.AuditService;
import com.octopyder.payroll.common.AppException;
import com.octopyder.payroll.common.BusinessClock;
import com.octopyder.payroll.org.dto.OfficeLocationDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OfficeLocationService {

    private static final Pattern CIDR_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}/([0-9]|[1-2][0-9]|3[0-2])$");

    private final OfficeLocationRepository repository;
    private final AuditService auditService;
    private final BusinessClock businessClock;

    public OfficeLocationService(
        OfficeLocationRepository repository,
        AuditService auditService,
        BusinessClock businessClock
    ) {
        this.repository = repository;
        this.auditService = auditService;
        this.businessClock = businessClock;
    }

    public List<OfficeLocationDto> listActive() {
        return repository.findByActiveTrue().stream().map(this::toDto).toList();
    }

    public List<OfficeLocationDto> listAll() {
        return repository.findAll().stream().map(this::toDto).toList();
    }

    @Transactional
    public OfficeLocationDto create(OfficeLocationDto.CreateRequest request) {
        validateRequest(request.name(), request.latitude(), request.longitude(), request.radiusM(), request.allowedIpCidrs());

        OfficeLocation loc = new OfficeLocation(
            request.name().trim(),
            request.latitude(),
            request.longitude(),
            request.radiusM(),
            request.allowedIpCidrs()
        );
        loc.setCreatedAt(businessClock.instant());
        loc.setUpdatedAt(businessClock.instant());

        OfficeLocation saved = repository.save(loc);
        auditService.log("CREATE_OFFICE_LOCATION", "OFFICE_LOCATION", saved.getId().toString(), null, "{\"name\":\"" + saved.getName() + "\"}");
        return toDto(saved);
    }

    @Transactional
    public OfficeLocationDto update(UUID id, OfficeLocationDto.CreateRequest request) {
        OfficeLocation loc = repository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Office location not found"));

        validateRequest(request.name(), request.latitude(), request.longitude(), request.radiusM(), request.allowedIpCidrs());

        loc.setName(request.name().trim());
        loc.setLatitude(request.latitude());
        loc.setLongitude(request.longitude());
        loc.setRadiusM(request.radiusM());
        loc.setAllowedIpCidrs(request.allowedIpCidrs());
        loc.setUpdatedAt(businessClock.instant());

        OfficeLocation saved = repository.save(loc);
        auditService.log("UPDATE_OFFICE_LOCATION", "OFFICE_LOCATION", saved.getId().toString(), null, "{\"name\":\"" + saved.getName() + "\"}");
        return toDto(saved);
    }

    @Transactional
    public void deactivate(UUID id) {
        OfficeLocation loc = repository.findById(id)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Office location not found"));

        loc.setActive(false);
        loc.setUpdatedAt(businessClock.instant());
        repository.save(loc);

        auditService.log("DEACTIVATE_OFFICE_LOCATION", "OFFICE_LOCATION", id.toString(), null, "Office location deactivated");
    }

    private void validateRequest(String name, BigDecimal lat, BigDecimal lng, Integer radiusM, List<String> allowedIpCidrs) {
        if (name == null || name.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Location name is required");
        }
        if (name.length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Location name length cannot exceed 100 characters");
        }
        if (lat == null || lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES", "Latitude must be between -90 and 90");
        }
        if (lng == null || lng.compareTo(BigDecimal.valueOf(-180)) < 0 || lng.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_COORDINATES", "Longitude must be between -180 and 180");
        }
        if (radiusM == null || radiusM <= 0) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_RADIUS", "Radius must be greater than 0");
        }
        if (allowedIpCidrs != null) {
            for (String cidr : allowedIpCidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    String trimmed = cidr.trim();
                    if (!CIDR_PATTERN.matcher(trimmed).matches()) {
                        throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_CIDR", "Invalid CIDR notation: " + trimmed);
                    }
                }
            }
        }
    }

    private OfficeLocationDto toDto(OfficeLocation loc) {
        return new OfficeLocationDto(
            loc.getId(),
            loc.getName(),
            loc.getLatitude(),
            loc.getLongitude(),
            loc.getRadiusM(),
            loc.getAllowedIpCidrs(),
            loc.getActive(),
            loc.getCreatedAt(),
            loc.getUpdatedAt()
        );
    }
}
