package com.octopyder.payroll.leave;

import com.octopyder.payroll.attendance.LeaveQueryService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Primary
public class LeaveQueryServiceImpl implements LeaveQueryService {

    private final LeaveRequestRepository leaveRequestRepository;

    public LeaveQueryServiceImpl(LeaveRequestRepository leaveRequestRepository) {
        this.leaveRequestRepository = leaveRequestRepository;
    }

    @Override
    public boolean hasApprovedLeave(UUID userId, LocalDate date) {
        return leaveRequestRepository.hasApprovedLeaveOnDate(userId, date);
    }

    @Override
    public int countApprovedLeaveDays(UUID userId, LocalDate from, LocalDate to) {
        List<LeaveRequest> list = leaveRequestRepository.findApprovedInDateRange(userId, from, to);
        BigDecimal total = BigDecimal.ZERO;
        for (LeaveRequest req : list) {
            total = total.add(req.getDays());
        }
        return total.intValue();
    }
}
