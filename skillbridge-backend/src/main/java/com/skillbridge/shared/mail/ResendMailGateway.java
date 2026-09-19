package com.skillbridge.shared.mail;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Resend's HTTPS API: {@code POST /emails} with a bearer key.
 *
 * <p>Resend only sends from a domain verified with it, and this project has
 * none yet, so this adapter has never delivered a real message. What is tested
 * ({@code ResendMailGatewayTest}) is the request it makes and how it reports a
 * refusal, against a mocked server.
 */
class ResendMailGateway implements MailGateway {

    private final RestClient client;
    private final String from;

    ResendMailGateway(RestClient client, String from) {
        this.client = client;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        try {
            client.post()
                    .uri("/emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(message.to()),
                            "subject", message.subject(),
                            "text", message.text()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // Resend's error bodies name the problem ("domain is not verified")
            // and carry nothing secret, so they are worth keeping.
            throw new MailDeliveryException("Resend refused mail to " + message.to() + ": HTTP "
                    + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new MailDeliveryException("Resend unreachable for mail to " + message.to() + ": " + e.getMessage(), e);
        }
    }
}
