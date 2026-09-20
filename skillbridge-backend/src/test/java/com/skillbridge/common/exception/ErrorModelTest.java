package com.skillbridge.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One error model: every failure answers with {@code ErrorResponse}, and a
 * failure that is ours says nothing but a correlation id.
 *
 * <p>Until 2026-09-20 {@code IllegalArgumentException} answered 400 and
 * {@code IllegalStateException} 409, both echoing {@code ex.getMessage()}: a
 * library's message, written for a developer, handed to whoever sent the
 * request, and a bug of ours reported as the caller's fault.
 */
class ErrorModelTest {

    private static final String LEAK = "column \"secret_column\" of relation \"users\"";

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(handler())
            .build();

    private static GlobalExceptionHandler handler() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "maxFileSize", "1MB");
        return handler;
    }

    @Test
    @DisplayName("an IllegalArgumentException is a bug: 500, a correlation id, and none of its message")
    void illegalArgumentIsOurs() throws Exception {
        assertOurFault(perform("illegal-argument"));
    }

    @Test
    @DisplayName("an IllegalStateException is a bug too, not a 409")
    void illegalStateIsOurs() throws Exception {
        assertOurFault(perform("illegal-state"));
    }

    @Test
    @DisplayName("a message written for the caller is still shown, with its own code")
    void deliberateMessagesSurvive() throws Exception {
        perform("bad-request")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Upload a .csv file."))
                .andExpect(jsonPath("$.path").value("/boom/bad-request"));
    }

    @Test
    @DisplayName("a 5xx we raise ourselves also says nothing but the reference")
    void serverSideApiExceptionSaysNothing() throws Exception {
        assertOurFault(perform("internal"));
    }

    @Test
    @DisplayName("an upload over the limit is 413, naming the limit")
    void tooLarge() throws Exception {
        perform("too-large")
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.message").value(containsString("1MB")));
    }

    private ResultActions perform(String kind) throws Exception {
        return mvc.perform(get("/boom/{kind}", kind).param("leak", LEAK));
    }

    private void assertOurFault(ResultActions result) throws Exception {
        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret_column"))))
                .andExpect(jsonPath("$.details.correlationId").value(matchesPattern("[0-9a-f]{8}")))
                .andExpect(jsonPath("$.message").value(containsString("Quote reference")))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(500));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/boom/{kind}")
        void boom(@org.springframework.web.bind.annotation.PathVariable String kind,
                  @RequestParam String leak) {
            switch (kind) {
                case "illegal-argument" -> throw new IllegalArgumentException(leak);
                case "illegal-state" -> throw new IllegalStateException(leak);
                case "bad-request" -> throw new BadRequestException("Upload a .csv file.");
                case "internal" -> throw new InternalServerException(leak);
                case "too-large" -> throw new MaxUploadSizeExceededException(1_048_576L);
                default -> throw new UnsupportedOperationException(kind);
            }
        }
    }
}
