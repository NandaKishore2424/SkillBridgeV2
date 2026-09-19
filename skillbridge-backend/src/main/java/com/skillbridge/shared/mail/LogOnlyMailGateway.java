package com.skillbridge.shared.mail;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends nothing. Records that a message would have gone, to whom and about
 * what -- never the body, which may hold a temporary password.
 *
 * <p>Until 2026-09-19 this was all the application had, and it logged the body:
 * every invitation's temporary password went into the log in plain text.
 */
@Slf4j
class LogOnlyMailGateway implements MailGateway {

    @Override
    public void send(MailMessage message) {
        log.info("Mail not sent (app.mail.provider=LOG): to={} subject=\"{}\"", message.to(), message.subject());
    }
}
