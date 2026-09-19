package com.skillbridge.auth.service;

import com.skillbridge.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JJWT 0.12 chooses the HMAC algorithm from the key length, so the algorithm is
 * a fact about the configured key rather than about the code. The comment in
 * {@link JwtService} used to say HS256; for a 48-byte key it is HS384.
 */
class JwtServiceAlgorithmTest {

    /** 48 bytes: the committed test-only key from application-test.yaml. */
    private static final String KEY_48 = "dGVzdC1vbmx5LWp3dC1zaWduaW5nLWtleS1ub3QtYS1yZWFsLXNlY3JldC0wMDAw";

    @Test
    @DisplayName("a 48-byte key, the length the setup instructions generate, signs HS384")
    void fortyEightByteKeySignsHs384() {
        assertThat(Base64.getDecoder().decode(KEY_48)).hasSize(48);

        User user = User.builder().id(1L).email("u@example.invalid").collegeId(1L).isActive(true).build();
        String token = new JwtService(KEY_48, 900).generateAccessToken(user, "STUDENT", Set.of("STUDENT"), false);

        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS384\"");
    }
}
