package com.skillbridge.shared.mail;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP through {@code spring.mail.*}. Locally and for the demo, that is Mailpit. */
class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final String from;

    SmtpMailGateway(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());
        try {
            sender.send(mail);
        } catch (MailException e) {
            throw new MailDeliveryException("SMTP send to " + message.to() + " failed: " + e.getMessage(), e);
        }
    }
}
