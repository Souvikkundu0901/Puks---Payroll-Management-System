package com.octopyder.payroll.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuditedAspect {

    private final AuditService auditService;

    public AuditedAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();
        try {
            auditService.log(audited.action(), audited.entityType(), null, null, null);
        } catch (Exception ignored) {
            // Audit aspect should not disrupt happy path if manual logging took care of it
        }
        return result;
    }
}
