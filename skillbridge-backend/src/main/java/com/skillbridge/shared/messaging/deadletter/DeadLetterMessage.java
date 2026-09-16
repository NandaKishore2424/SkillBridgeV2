package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.LongString;
import com.skillbridge.common.config.RabbitMQConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What {@link DeadLetterRecorder} stores about one message from the DLQ.
 *
 * <h2>Total, on purpose</h2>
 *
 * <p>{@link #from} must turn <em>any</em> message into a row Postgres will accept.
 * The recorder consumes the DLQ one message at a time and does not drop what it
 * cannot store, so a message that made the insert fail would block every dead
 * letter behind it. The messages most likely to end up here are exactly the
 * malformed ones, so this class assumes nothing:
 *
 * <ul>
 *   <li>a body that is not UTF-8, or contains a NUL byte (which a Postgres
 *       {@code TEXT} refuses), is stored base64-encoded;</li>
 *   <li>{@code payload_json} is set only for JSON that {@code jsonb} will take:
 *       no NUL, no unpaired surrogate;</li>
 *   <li>header values of any AMQP type become JSON-safe values, and strings lose
 *       their NULs;</li>
 *   <li>strings are cut to their column widths.</li>
 * </ul>
 *
 * <h2>Where the reason comes from</h2>
 *
 * <p>If the consumer dead-lettered the message itself it set
 * {@link RabbitMQConfig#FAILURE_REASON_HEADER}, and that is the reason. Otherwise
 * the broker did, and the most recent {@code x-death} entry says how; the reason
 * then explains what that usually means, because "rejected" alone tells an
 * operator nothing.
 *
 * <h2>The fingerprint</h2>
 *
 * <p>SHA-256 of the message id, the failure, and the body — and deliberately not
 * the failure time. A recorder that dies before acking sees the same message
 * again, and the same event failing the same way twice (a duplicate delivery of
 * it) is one thing to decide about, not two.
 */
public record DeadLetterMessage(
        String fingerprint,
        UUID eventId,
        String eventType,
        String routingKey,
        String sourceQueue,
        String deathReason,
        String failureReason,
        int retryCount,
        String payload,
        String payloadEncoding,
        String payloadJson,
        String headersJson,
        Instant failedAt) {

    /** {@link #deathReason} when the consumer dead-lettered the message and said why. */
    public static final String CONSUMER = "consumer";
    public static final String UNKNOWN = "unknown";

    static final int EVENT_TYPE_WIDTH = 100;
    static final int NAME_WIDTH = 255;
    static final int DEATH_REASON_WIDTH = 50;
    private static final int MAX_HEADER_DEPTH = 8;

    public static DeadLetterMessage from(Message message, ObjectMapper json, Instant receivedAt) {
        MessageProperties props = message.getMessageProperties();
        Map<String, Object> headers = props.getHeaders() != null ? props.getHeaders() : Map.of();
        byte[] body = message.getBody() != null ? message.getBody() : new byte[0];

        String text = storableText(body);
        String payload = text != null ? text : Base64.getEncoder().encodeToString(body);
        JsonNode tree = text != null ? parse(json, text) : null;
        String payloadJson = tree != null ? jsonbSafe(json, tree) : null;

        String messageId = clean(props.getMessageId());
        UUID eventId = uuid(messageId).or(() -> uuid(string(headers.get("eventId")))).orElse(null);
        String eventType = clip(firstNonBlank(
                string(headers.get("eventType")),
                tree != null && tree.path("eventType").isTextual() ? clean(tree.path("eventType").textValue()) : null),
                EVENT_TYPE_WIDTH);

        String deathReason;
        String failureReason;
        String sourceQueue;
        Instant failedAt;
        String deathKey;

        String consumerReason = string(headers.get(RabbitMQConfig.FAILURE_REASON_HEADER));
        List<Map<String, ?>> deaths = xDeath(props);
        if (consumerReason != null) {
            deathReason = CONSUMER;
            failureReason = clip(consumerReason, RabbitMQConfig.MAX_FAILURE_REASON_LENGTH);
            sourceQueue = clip(string(headers.get(RabbitMQConfig.FAILED_QUEUE_HEADER)), NAME_WIDTH);
            failedAt = epochMillis(headers.get(RabbitMQConfig.FAILED_AT_HEADER)).orElse(receivedAt);
            deathKey = CONSUMER + "\n" + failureReason + "\n" + nullToEmpty(sourceQueue);
        } else if (!deaths.isEmpty()) {
            // RabbitMQ keeps the most recent death first.
            Map<String, ?> latest = deaths.get(0);
            String reason = firstNonBlank(string(latest.get("reason")), UNKNOWN);
            deathReason = clip(reason, DEATH_REASON_WIDTH);
            sourceQueue = clip(string(latest.get("queue")), NAME_WIDTH);
            failedAt = instant(latest.get("time")).orElse(receivedAt);
            failureReason = clip(describeBrokerDeath(reason, sourceQueue, number(latest.get("count"))),
                    RabbitMQConfig.MAX_FAILURE_REASON_LENGTH);
            deathKey = "broker\n" + reason + "\n" + nullToEmpty(sourceQueue);
        } else {
            deathReason = UNKNOWN;
            sourceQueue = null;
            failedAt = receivedAt;
            failureReason = "No failure reason and no x-death header: something published this "
                    + "straight to the dead-letter exchange without saying why.";
            deathKey = UNKNOWN;
        }

        return new DeadLetterMessage(
                fingerprint(nullToEmpty(messageId), deathKey, body),
                eventId,
                eventType,
                clip(clean(props.getReceivedRoutingKey()), NAME_WIDTH),
                sourceQueue,
                deathReason,
                failureReason,
                nonNegativeInt(headers.get(RabbitMQConfig.RETRY_ATTEMPT_HEADER)),
                payload,
                text != null ? DeadLetterEvent.UTF8 : DeadLetterEvent.BASE64,
                payloadJson,
                headersJson(json, headers),
                failedAt);
    }

    /**
     * The row to write when {@link #from} itself failed. It should not; if it does,
     * the message is still kept, with the body base64-encoded and the error as the reason.
     */
    public static DeadLetterMessage unreadable(Message message, Throwable error, Instant receivedAt) {
        byte[] body = message.getBody() != null ? message.getBody() : new byte[0];
        String reason = clip(clean("The recorder could not read this message: " + error),
                RabbitMQConfig.MAX_FAILURE_REASON_LENGTH);
        return new DeadLetterMessage(
                fingerprint("", "unreadable", body),
                null, null, null, null, UNKNOWN, reason, 0,
                Base64.getEncoder().encodeToString(body), DeadLetterEvent.BASE64, null, null, receivedAt);
    }

    // ------------------------------------------------------------ body

    /** The body as text, or null if it is not strict UTF-8 or holds a NUL byte. */
    static String storableText(byte[] body) {
        for (byte b : body) {
            if (b == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static JsonNode parse(ObjectMapper json, String text) {
        try {
            JsonNode tree = json.readTree(text);
            return tree == null || tree.isMissingNode() ? null : tree;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The tree re-serialised, if {@code jsonb} will accept it.
     *
     * <p>Jackson accepts two things Postgres refuses: the escape for NUL, and an
     * unpaired surrogate. Either would fail the insert of the whole row.
     */
    private static String jsonbSafe(ObjectMapper json, JsonNode tree) {
        try {
            String out = json.writeValueAsString(tree);
            if (out.toLowerCase().contains("\\u0000") || hasUnpairedSurrogate(out)) {
                return null;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ headers

    @SuppressWarnings("unchecked")
    private static List<Map<String, ?>> xDeath(MessageProperties props) {
        try {
            List<Map<String, ?>> deaths = props.getXDeathHeader();
            return deaths != null ? deaths : List.of();
        } catch (RuntimeException e) {
            // A publisher can put anything in a header called x-death.
            return List.of();
        }
    }

    private static String headersJson(ObjectMapper json, Map<String, Object> headers) {
        if (headers.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(jsonSafe(headers, 0));
        } catch (Exception e) {
            return null;
        }
    }

    /** Any AMQP header value as something Jackson writes and {@code jsonb} accepts. */
    static Object jsonSafe(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_HEADER_DEPTH) {
            return "(nested too deeply to record)";
        }
        if (value instanceof Double d && !Double.isFinite(d) || value instanceof Float f && !Float.isFinite(f)) {
            return String.valueOf(value);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof byte[] bytes) {
            return Map.of("base64", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(nullToEmpty(clean(String.valueOf(k))), jsonSafe(v, depth + 1)));
            return out;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            items.forEach(item -> out.add(jsonSafe(item, depth + 1)));
            return out;
        }
        if (value instanceof Object[] items) {
            List<Object> out = new ArrayList<>();
            for (Object item : items) {
                out.add(jsonSafe(item, depth + 1));
            }
            return out;
        }
        return string(value);
    }

    /**
     * A header value as a clean string.
     *
     * <p>Not just {@code String.valueOf}. Spring AMQP converts a header string to a
     * {@code String} only up to 1024 bytes, and the consumer's failure reasons run to
     * 2000 characters. Anything longer arrives unconverted: as the client's
     * {@link LongString} by default, or as a {@code DataInputStream} if the converter
     * is configured to turn long strings into streams -- and a stream's
     * {@code toString} is its class and hash, not its text. (A LongString's
     * {@code toString} does return the text in amqp-client 5.25; decoding its bytes
     * explicitly does not depend on that.)
     */
    static String string(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return clean(s);
        }
        if (value instanceof LongString longString) {
            return clean(new String(longString.getBytes(), StandardCharsets.UTF_8));
        }
        if (value instanceof byte[] bytes) {
            return clean(new String(bytes, StandardCharsets.UTF_8));
        }
        if (value instanceof InputStream in) {
            try (in) {
                return clean(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                return null;
            }
        }
        return clean(String.valueOf(value));
    }

    private static Optional<Instant> instant(Object value) {
        if (value instanceof Date date) {
            return Optional.of(date.toInstant());
        }
        return epochMillis(value);
    }

    private static Optional<Instant> epochMillis(Object value) {
        long millis;
        if (value instanceof Number n) {
            millis = n.longValue();
        } else {
            try {
                millis = Long.parseLong(String.valueOf(string(value)).trim());
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        // 2001 to 2286: outside that it is not a millisecond timestamp, whatever it is.
        return millis > 1_000_000_000_000L && millis < 10_000_000_000_000L
                ? Optional.of(Instant.ofEpochMilli(millis)) : Optional.empty();
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(string(value)).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int nonNegativeInt(Object value) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(value)));
    }

    private static Optional<UUID> uuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------ text

    static String describeBrokerDeath(String reason, String queue, long count) {
        String where = queue != null ? queue : "its queue";
        String times = count > 1 ? " (" + count + " times)" : "";
        return switch (reason) {
            case "rejected" -> "Rejected in " + where + " without requeue" + times + ". The AI service "
                    + "publishes its own reason when it can, so a bare rejection usually means that publish "
                    + "failed: look for this message id in its logs.";
            case "delivery_limit" -> "Redelivered from " + where + " past its delivery limit" + times
                    + " without ever being acknowledged: the consumer most likely crashed or was killed "
                    + "while processing it, every time.";
            case "expired" -> "Expired in " + where + times + " before anything consumed it.";
            case "maxlen" -> "Pushed out of " + where + times + " because the queue was full.";
            default -> "Dead-lettered by the broker from " + where + times + ": " + reason + ".";
        };
    }

    /** No NULs (Postgres text refuses them) and no unpaired surrogates (the driver would mangle them). */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean bad = c == '\0'
                    || Character.isHighSurrogate(c) && (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
                    || Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(value.charAt(i - 1)));
            if (bad && out == null) {
                out = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (out != null) {
                out.append(bad ? '�' : c);
            }
        }
        return out != null ? out.toString() : value;
    }

    /** At most {@code width} chars, never splitting a surrogate pair. */
    static String clip(String value, int width) {
        if (value == null || value.length() <= width) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(width - 1)) ? width - 1 : width;
        return value.substring(0, end);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        return !value.equals(clean(value.replace('\0', ' ')));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    static String fingerprint(String messageId, String deathKey, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(messageId.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(deathKey.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(body);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
