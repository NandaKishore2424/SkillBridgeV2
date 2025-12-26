package com.skillbridge.shared.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.bulkupload.dto.BulkUploadResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    public void sendWelcomeEmail(User user, String temporaryPassword) {
        // In a real application, this would use JavaMailSender to send an actual email
        log.info("----------------------------------------------------------------");
        log.info("SENDING WELCOME EMAIL TO: {}", user.getEmail());
        log.info("SUBJECT: Welcome to SkillBridge - Your Account is Ready");
        log.info("BODY:");
        log.info("Hi {},", user.getEmail()); // Using email as name for now until we have profile
        log.info("Your SkillBridge account has been created.");
        log.info("Login Credentials:");
        log.info("- Email: {}", user.getEmail());
        log.info("- Temporary Password: {}", temporaryPassword);
        log.info("IMPORTANT: You will be required to change your password on first login.");
        log.info("----------------------------------------------------------------");
    }

    public void sendPasswordResetEmail(User user, String resetToken) {
        log.info("----------------------------------------------------------------");
        log.info("SENDING PASSWORD RESET EMAIL TO: {}", user.getEmail());
        log.info("SUBJECT: Password Reset Request");
        log.info("Reset Token: {}", resetToken);
        log.info("----------------------------------------------------------------");
    }

    public void sendProfileCompletionReminder(User user) {
        log.info("----------------------------------------------------------------");
        log.info("SENDING REMINDER EMAIL TO: {}", user.getEmail());
        log.info("SUBJECT: Complete Your SkillBridge Profile");
        log.info("----------------------------------------------------------------");
    }

    public void sendBulkUploadReport(User admin, BulkUploadResponse report) {
        log.info("----------------------------------------------------------------");
        log.info("SENDING UPLOAD REPORT TO: {}", admin.getEmail());
        log.info("Upload ID: {}", report.getUploadId());
        log.info("Status: {}", report.getStatus());
        log.info("Total Rows: {}", report.getTotalRows());
        log.info("Success: {}", report.getSuccessfulRows());
        log.info("Failed: {}", report.getFailedRows());
        log.info("----------------------------------------------------------------");
    }
}
