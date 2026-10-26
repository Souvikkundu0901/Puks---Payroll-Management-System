package com.octopyder.payroll.org.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfficeLocationDto(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
    Integer radiusM,
    List<String> allowedIpCidrs,
    Boolean active,
    Instant createdAt,
    Instant updatedAt
) {
    public record CreateRequest(
        @NotBlank(message = "Office name is required")
        String name,

        @NotNull(message = "Latitude is required")
        @DecimalMin(value = "-90.0", message = "Latitude must be between -90 and 90")
        @DecimalMax(value = "90.0", message = "Latitude must be between -90 and 90")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required")
        @DecimalMin(value = "-180.0", message = "Longitude must be between -180 and 180")
        @DecimalMax(value = "180.0", message = "Longitude must be between -180 and 180")
        BigDecimal longitude,

        @NotNull(message = "Radius is required")
        @Min(value = 1, message = "Radius must be greater than 0")
        Integer radiusM,

        List<String> allowedIpCidrs
    ) {}
}
