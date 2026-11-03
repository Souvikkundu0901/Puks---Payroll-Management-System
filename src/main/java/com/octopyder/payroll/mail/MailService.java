package com.octopyder.payroll.mail;

public interface MailService {
    /**
     * Sends temporary login credentials to the user.
     *
     * @param toEmail destination email address
     * @param temporaryPassword plaintext temporary password to send
     * @return true if email was sent successfully, false otherwise
     */
    boolean sendLoginCredentials(String toEmail, String temporaryPassword);
}
