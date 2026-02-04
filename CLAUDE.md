# Claude Code Notes for Morel

This file contains notes and tips for Claude Code when working on this project.

## Code Style

### Multi-line String Formatting

When writing multi-line strings in Java tests, add `//` after the first
line's `"\n"` to prevent Google Java Format from joining the lines:

```java
// Good - formatter will preserve line breaks
final String input =
    "val x = 42;\n" //
        + "x + 1;\n";

// Bad - formatter will join into one line, causing "broken string" lint error
final String input =
    "val x = 42;\n"
        + "x + 1;\n";
```

This is enforced by the `LintTest` which checks for "broken string" violations.
