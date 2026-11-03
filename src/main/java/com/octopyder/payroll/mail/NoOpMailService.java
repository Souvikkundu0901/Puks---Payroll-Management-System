package com.octopyder.payroll.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "mail.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(NoOpMailService.class);

    @Override
    public boolean sendLoginCredentials(String toEmail, String temporaryPassword) {
        log.debug("Mail delivery is disabled (mail.enabled=false). Skipping email to {}", toEmail);
        return false;
    }
}
