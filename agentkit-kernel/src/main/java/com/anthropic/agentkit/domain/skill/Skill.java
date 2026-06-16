package com.anthropic.agentkit.domain.skill;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable knowledge skill loaded from a SKILL.md directory.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public record Skill(String name, String description, String body, Path baseDir) {

    public static final int MAX_DESCRIPTION_LENGTH = 1024;
    public static final int MAX_BODY_LENGTH = 64 * 1024;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(baseDir, "baseDir");

        name = name.trim();
        description = description.trim();
        validateName(name);
        validateDescription(description);
        validateBody(body);
        baseDir = baseDir.normalize();
        validateBaseDir(name, baseDir);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    private static void validateName(String name) {
        if (!isValidName(name)) {
            throw new IllegalArgumentException("invalid skill name: " + name);
        }
    }

    private static void validateDescription(String description) {
        if (description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("skill description must be 1..1024 characters");
        }
    }

    private static void validateBody(String body) {
        if (body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("skill body must be 1..65536 characters");
        }
    }

    private static void validateBaseDir(String name, Path baseDir) {
        Path fileName = baseDir.getFileName();
        if (fileName == null || !name.equals(fileName.toString())) {
            throw new IllegalArgumentException("skill name must match directory: " + name);
        }
    }
}
