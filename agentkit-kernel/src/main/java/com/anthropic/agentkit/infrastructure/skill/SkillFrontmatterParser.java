package com.anthropic.agentkit.infrastructure.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses the YAML frontmatter contract used by SKILL.md files.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class SkillFrontmatterParser {

    private static final String FRONTMATTER_DELIMITER = "---";
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public ParsedSkill parse(Path file, String content) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(content, "content");
        FrontmatterParts parts = splitFrontmatter(file, content);
        Map<String, Object> metadata = readMetadata(file, parts.yaml());
        String description = requireText(file, metadata, "description");
        Optional<String> name = optionalText(metadata, "name");
        return new ParsedSkill(name, description, parts.body());
    }

    private static FrontmatterParts splitFrontmatter(Path file, String content) {
        String normalized = content.replace("\r\n", "\n");
        if (!normalized.startsWith(FRONTMATTER_DELIMITER + "\n")) {
            throw new IllegalArgumentException("missing frontmatter: " + file);
        }
        int end = normalized.indexOf("\n" + FRONTMATTER_DELIMITER, 4);
        if (end < 0) {
            throw new IllegalArgumentException("missing frontmatter end: " + file);
        }
        String yaml = normalized.substring(4, end);
        String body = normalized.substring(end + 4).stripLeading();
        return new FrontmatterParts(yaml, body);
    }

    private Map<String, Object> readMetadata(Path file, String yaml) {
        try {
            return mapper.readValue(yaml, new TypeReference<>() {
            });
        } catch (IOException ex) {
            throw new IllegalArgumentException("invalid skill frontmatter: " + file, ex);
        }
    }

    private static String requireText(Path file, Map<String, Object> metadata, String key) {
        return optionalText(metadata, key)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("missing " + key + ": " + file));
    }

    private static Optional<String> optionalText(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    private record FrontmatterParts(String yaml, String body) {
    }

    public record ParsedSkill(Optional<String> name, String description, String body) {

        public ParsedSkill {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(body, "body");
        }
    }
}
