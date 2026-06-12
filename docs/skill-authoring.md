# Skill Authoring Guide

> @author zhourui(V33215020)
> @since 2026-06-13

Skills are knowledge packages for diagnosis SOPs and domain background. They are read-only: a Skill may explain which Java tools to use and how to interpret evidence, but it must not contain executable scripts or replace tool governance.

## Directory Layout

```text
skills-root/
  es-slow-query/
    SKILL.md
    references/
      index-mapping-cheatsheet.md
```

The directory name is the Skill name. It must match `^[a-z0-9][a-z0-9-]{0,63}$`.

## SKILL.md Contract

```markdown
---
name: es-slow-query
description: Diagnose slow ES queries when took, timeout, or P99 spikes appear.
allowed-tools:
  - Read
---

# ES Slow Query

1. Confirm the affected index and time window.
2. Use the ES read tool to collect query shape and profile output.
3. Read `references/index-mapping-cheatsheet.md` when mapping analysis is needed.
```

Rules:

- `description` is required and should say what the Skill does and when to use it.
- `name` is optional; when present it must equal the directory name.
- Unknown frontmatter fields are accepted and ignored for forward compatibility.
- Body size is limited to 64 KiB; move large reference material into files under the Skill directory.
- Reference files should use relative paths from the Skill directory and be read with the existing `Read` tool.

## PromptPack Boundary

Use PromptPack for information every diagnosis must read, such as global evidence rules and output discipline. Use Skill for scenario-specific SOPs, service background, error-code tables, and optional reference material.
