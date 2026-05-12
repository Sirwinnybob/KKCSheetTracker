## 2024-05-12 - Background File I/O in Jetpack Compose
**Learning:** Found synchronous disk I/O (file reading/JSON parsing) inside `remember` blocks in `LazyColumn` items during recomposition across multiple list screens. This leads to severe frame drops and jank during fast scrolling.
**Action:** When performing file checks (`File.exists()`, `File.listFiles()`) or reading JSON configs per list item in Compose, offload the work using `produceState` with `withContext(Dispatchers.IO)` instead of a simple `remember` block.
