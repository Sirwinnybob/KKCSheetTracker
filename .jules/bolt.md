## 2026-07-07 - Cross-platform test execution in Android projects
**Learning:** Hardcoded absolute paths like `D:/cache/part.png` in unit tests will fail on CI/CD or other developer machines (like linux/macOS) because they are not treated as absolute paths by `java.io.File`.
**Action:** When creating tests that test absolute paths, either use platform-specific paths conditionally or use standard java absolute paths like `File("/tmp/some/file.txt")` that work effectively across systems.
