package com.anthropic.agentkit.infrastructure.tools.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared bounded JSON response contract for diagnosis HTTP adapters.
 *
 * @author alex
 */
final class BoundedJsonHttpTransport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BoundedJsonHttpTransport() {
    }

    static JsonNode send(HttpClient client, HttpRequest request, int maxBodyBytes)
            throws IOException {
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            return readResponse(response, maxBodyBytes);
        } catch (HttpTimeoutException exception) {
            throw failure(BackendErrorCode.TIMED_OUT, true,
                    "backend request timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(BackendErrorCode.UNAVAILABLE, false,
                    "backend request interrupted", exception);
        } catch (BackendQueryException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(BackendErrorCode.CONNECTION_FAILED, true,
                    "backend connection failed", exception);
        }
    }

    private static JsonNode readResponse(HttpResponse<InputStream> response, int maxBodyBytes)
            throws IOException {
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw statusFailure(response.statusCode());
            }
            verifyContentType(response);
            verifyDeclaredSize(response, maxBodyBytes);
            byte[] bytes = body.readNBytes(maxBodyBytes + 1);
            if (bytes.length > maxBodyBytes) {
                throw failure(BackendErrorCode.RESPONSE_TOO_LARGE, false,
                        "backend response exceeded the configured limit");
            }
            return parse(bytes);
        }
    }

    private static void verifyContentType(HttpResponse<?> response)
            throws BackendQueryException {
        String raw = response.headers().firstValue("Content-Type").orElse("");
        String[] parts = raw.split(";");
        String mediaType = parts[0].trim().toLowerCase(Locale.ROOT);
        boolean json = mediaType.equals("application/json")
                || mediaType.startsWith("application/") && mediaType.endsWith("+json");
        if (!json) {
            throw failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "backend response content type is unsupported");
        }
        for (int index = 1; index < parts.length; index++) {
            verifyCharset(parts[index]);
        }
    }

    private static void verifyCharset(String parameter) throws BackendQueryException {
        String[] pair = parameter.trim().split("=", 2);
        if (pair.length != 2 || !pair[0].trim().equalsIgnoreCase("charset")) {
            return;
        }
        String charset = pair[1].trim().replace("\"", "");
        if (!charset.equalsIgnoreCase("utf-8") && !charset.equalsIgnoreCase("utf8")) {
            throw failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "backend response charset must be UTF-8");
        }
    }

    private static void verifyDeclaredSize(HttpResponse<?> response, int maxBodyBytes)
            throws BackendQueryException {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > maxBodyBytes) {
            throw failure(BackendErrorCode.RESPONSE_TOO_LARGE, false,
                    "backend response exceeded the configured limit");
        }
    }

    private static JsonNode parse(byte[] bytes) throws BackendQueryException {
        try {
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = JSON.readTree(content);
            if (root == null) {
                throw new JsonProcessingException("empty response") { };
            }
            return root;
        } catch (CharacterCodingException | JsonProcessingException exception) {
            throw failure(BackendErrorCode.PROTOCOL_ERROR, false,
                    "backend response JSON is invalid", exception);
        }
    }

    private static BackendQueryException statusFailure(int status) {
        if (status == 401) {
            return failure(BackendErrorCode.AUTHENTICATION_FAILED, false,
                    "backend authentication failed");
        }
        if (status == 403) {
            return failure(BackendErrorCode.AUTHORIZATION_DENIED, false,
                    "backend authorization denied");
        }
        if (status == 408 || status == 504) {
            return failure(BackendErrorCode.TIMED_OUT, true, "backend request timed out");
        }
        if (status == 429) {
            return failure(BackendErrorCode.RATE_LIMITED, true, "backend rate limit reached");
        }
        if (status >= 500 && status <= 599) {
            return failure(BackendErrorCode.UNAVAILABLE, true,
                    "backend is temporarily unavailable");
        }
        return failure(status >= 400 && status <= 499
                        ? BackendErrorCode.INVALID_QUERY : BackendErrorCode.PROTOCOL_ERROR,
                false, "backend request failed");
    }

    static BackendQueryException failure(BackendErrorCode code, boolean retryable,
                                         String safeMessage) {
        return new BackendQueryException(new BackendFailure(code, retryable, safeMessage));
    }

    private static BackendQueryException failure(BackendErrorCode code, boolean retryable,
                                                  String safeMessage, Throwable cause) {
        return new BackendQueryException(new BackendFailure(code, retryable, safeMessage), cause);
    }
}
