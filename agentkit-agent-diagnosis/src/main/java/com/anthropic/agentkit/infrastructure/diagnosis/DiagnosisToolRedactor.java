package com.anthropic.agentkit.infrastructure.diagnosis;

import com.anthropic.agentkit.infrastructure.tools.governance.ToolRedactor;

import java.util.regex.Pattern;

/**
 * Conservative credential redactor applied to every diagnosis tool result.
 *
 * @author alex
 */
public final class DiagnosisToolRedactor implements ToolRedactor {

    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic|apikey)?\\s*[^\\s,;]+"
    );
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?key|private[_-]?key|token|password|secret|credential)"
                    + "\\s*[:=]\\s*(?:['\"]?)[^\\s,'\"}]+"
    );
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(bearer|basic|apikey)\\s+[^\\s,;]+"
    );
    private static final Pattern OPENAI_STYLE_SECRET = Pattern.compile(
            "(?i)\\bsk-[a-z0-9_-]{8,}\\b"
    );

    @Override
    public String redact(String content) {
        String value = content == null ? "" : content;
        value = AUTHORIZATION.matcher(value).replaceAll("$1***");
        value = CREDENTIAL.matcher(value).replaceAll("$1=***");
        value = AUTH_SCHEME.matcher(value).replaceAll("$1 ***");
        return OPENAI_STYLE_SECRET.matcher(value).replaceAll("***");
    }
}
