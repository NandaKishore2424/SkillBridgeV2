package com.skillbridge.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backstop that keeps a credential out of a log file.
 *
 * <p>A secret written to a log has been copied to wherever logs are shipped,
 * kept for however long logs are kept, and made readable by everyone who can
 * read logs. Rotating it afterwards is the only remedy, and only if somebody
 * notices — which is why this is worth having even though the real rule is that
 * nothing logs a credential in the first place.
 */
class CredentialRedactingConverterTest {

    @Test
    @DisplayName("credential-shaped keys lose their values, in either syntax")
    void redactsKeyedSecrets() {
        assertThat(CredentialRedactingConverter.redact("Login failed for password=hunter2 user=x"))
                .contains("password=[REDACTED]")
                .doesNotContain("hunter2");

        assertThat(CredentialRedactingConverter.redact("body {\"email\":\"a@b.c\",\"password\":\"s3cret!\"}"))
                .doesNotContain("s3cret!");

        assertThat(CredentialRedactingConverter.redact("header Authorization: abc123xyz"))
                .doesNotContain("abc123xyz");

        assertThat(CredentialRedactingConverter.redact("refresh_token=aaa.bbb.ccc rotated"))
                .doesNotContain("aaa.bbb.ccc");
    }

    @Test
    @DisplayName("a bearer token and a bare JWT are caught without a key beside them")
    void redactsUnkeyedTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.c2lnbmF0dXJlSGVyZQ";

        assertThat(CredentialRedactingConverter.redact("Authenticating with Bearer " + jwt))
                .as("a token arrives on the wire without a helpful label next to it")
                .doesNotContain(jwt);

        assertThat(CredentialRedactingConverter.redact("token was " + jwt + " at the time"))
                .as("three base64url segments separated by dots is a JWT wherever it appears")
                .doesNotContain(jwt);
    }

    @Test
    @DisplayName("redaction takes the secret and leaves the sentence")
    void keepsTheSurroundingContext() {
        String redacted = CredentialRedactingConverter.redact(
                "Refresh failed for user 42: token=abc.def.ghi expired at 10:15");

        assertThat(redacted)
                .as("""
                    A redactor that blanks the rest of the line removes the context that \
                    made the line worth logging, and people work around it by not logging \
                    at all.""")
                .contains("user 42")
                .contains("expired at 10:15")
                .doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("an ordinary message is left exactly as it was")
    void leavesOrdinaryMessagesAlone() {
        String message = "Graded 12 students on topic 7 in batch 1";
        assertThat(CredentialRedactingConverter.redact(message)).isEqualTo(message);

        assertThat(CredentialRedactingConverter.redact(null)).isNull();
        assertThat(CredentialRedactingConverter.redact("")).isEmpty();
    }
}
