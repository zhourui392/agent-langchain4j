# Project Notes

## Build Environment
- This repository is built and tested with Java 21.
- On this Windows machine, the default shell Java may be JDK 17. Set JDK 21 before running Maven:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.9'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -q verify
```

## Verification Commands
- Main verification:

```powershell
mvn -q verify
```

- Coverage report generation requires explicit JaCoCo goals. A normal `mvn verify` does not refresh JaCoCo reports in the current Maven configuration:

```powershell
mvn -q org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report
```

## Last Verified Result
- `mvn -q verify` passed with JDK 21.
- Test result: 369 tests, 0 failures, 0 errors, 0 skipped.
- JaCoCo total coverage from the explicit coverage run:
  - Line: 84.23%
  - Branch: 71.11%

## Non-blocking Warnings
- JDK 21 test runs may print ByteBuddy dynamic Java agent warnings.
- The JVM may also print a bootstrap classpath sharing warning.
- These warnings were present during the successful verification and did not fail the build.

## Working Tree Notes
- Do not commit `target/` build output or generated JaCoCo/Surefire reports.
- A local untracked `.claude/` directory may exist in the repo root; leave it uncommitted unless explicitly requested.
- Git may warn that `LF will be replaced by CRLF` on Windows. This is a line-ending configuration warning, not a compilation error.
