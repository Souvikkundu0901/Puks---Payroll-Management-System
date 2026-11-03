package com.octopyder.payroll.attendance;

import com.octopyder.payroll.org.OfficeLocation;
import com.octopyder.payroll.org.OfficeLocationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class GeofenceService {

    private final OfficeLocationRepository officeLocationRepository;

    public GeofenceService(OfficeLocationRepository officeLocationRepository) {
        this.officeLocationRepository = officeLocationRepository;
    }

    public record GeofenceMatchResult(
        AttendanceWorkMode workMode,
        UUID officeLocationId,
        String officeLocationName,
        BigDecimal distanceM
    ) {}

    public GeofenceMatchResult evaluate(BigDecimal clientLat, BigDecimal clientLon, String clientIp) {
        List<OfficeLocation> activeLocations = officeLocationRepository.findAll().stream()
            .filter(loc -> Boolean.TRUE.equals(loc.getActive()))
            .toList();

        // 1. Check coordinates against office geofence circle
        if (clientLat != null && clientLon != null) {
            double cLat = clientLat.doubleValue();
            double cLon = clientLon.doubleValue();

            for (OfficeLocation loc : activeLocations) {
                if (loc.getLatitude() != null && loc.getLongitude() != null) {
                    double dist = HaversineCalculator.distanceMeters(
                        cLat, cLon,
                        loc.getLatitude().doubleValue(),
                        loc.getLongitude().doubleValue()
                    );

                    int allowedRadius = loc.getRadiusM() != null ? loc.getRadiusM() : 100;
                    if (dist <= allowedRadius) {
                        return new GeofenceMatchResult(
                            AttendanceWorkMode.OFFICE,
                            loc.getId(),
                            loc.getName(),
                            BigDecimal.valueOf(dist).setScale(2, RoundingMode.HALF_UP)
                        );
                    }
                }
            }
        }

        // 2. Check office IP CIDR match
        if (clientIp != null && !clientIp.isBlank()) {
            for (OfficeLocation loc : activeLocations) {
                if (loc.getAllowedIpCidrs() != null && CidrMatcher.matches(clientIp, loc.getAllowedIpCidrs())) {
                    return new GeofenceMatchResult(
                        AttendanceWorkMode.OFFICE,
                        loc.getId(),
                        loc.getName(),
                        null
                    );
                }
            }
        }

        // 3. Fallback to REMOTE
        return new GeofenceMatchResult(
            AttendanceWorkMode.REMOTE,
            null,
            null,
            null
        );
    }
}
