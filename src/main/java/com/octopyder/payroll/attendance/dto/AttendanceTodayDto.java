package com.octopyder.payroll.attendance.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public record AttendanceTodayDto(
    boolean checkedIn,
    boolean checkedOut,
    @JsonProperty("todayRecord")
    @JsonAlias({"todayRecord", "record"})
    AttendanceResponse todayRecord,
    int hoursWorkedToday,
    int workedMinutes,
    int overtimeMinutes
) {
    @JsonProperty("record")
    public AttendanceResponse record() {
        return todayRecord;
    }
}
