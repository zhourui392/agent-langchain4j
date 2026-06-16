package com.anthropic.agentkit.infrastructure.skill;

import com.anthropic.agentkit.domain.skill.Skill;
import com.anthropic.agentkit.domain.skill.SkillSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads skills from immediate child directories containing SKILL.md.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public final class DirectorySkillSource implements SkillSource {

    private static final String SKILL_FILE = "SKILL.md";

    private final Path root;
    private final SkillFrontmatterParser parser;

    public DirectorySkillSource(Path root, SkillFrontmatterParser parser) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public List<Skill> load() {
        validateRoot();
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::loadSkill)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to load skills root: " + root, ex);
        }
    }

    private void validateRoot() {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("skills root must be a directory: " + root);
        }
    }

    private Skill loadSkill(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        ensureInsideRoot(normalized);
        String directoryName = normalized.getFileName().toString();
        SkillFrontmatterParser.ParsedSkill parsed = parseSkillFile(normalized);
        String skillName = parsed.name().orElse(directoryName);
        validateDirectoryName(directoryName, skillName);
        return createSkill(skillName, parsed, normalized);
    }

    private Skill createSkill(String skillName, SkillFrontmatterParser.ParsedSkill parsed, Path directory) {
        try {
            return new Skill(skillName, parsed.description(), parsed.body(), directory);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid skill file: " + directory.resolve(SKILL_FILE)
                    + " - " + ex.getMessage(), ex);
        }
    }

    private SkillFrontmatterParser.ParsedSkill parseSkillFile(Path directory) {
        Path file = directory.resolve(SKILL_FILE);
        try {
            return parser.parse(file, Files.readString(file));
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to read skill file: " + file, ex);
        }
    }

    private void ensureInsideRoot(Path normalized) {
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("skill directory escapes root: " + normalized);
        }
    }

    private static void validateDirectoryName(String directoryName, String skillName) {
        if (!Skill.isValidName(directoryName)) {
            throw new IllegalArgumentException("invalid skill name: " + directoryName);
        }
        if (!directoryName.equals(skillName)) {
            throw new IllegalArgumentException("skill name must match directory: " + directoryName);
        }
    }
}
