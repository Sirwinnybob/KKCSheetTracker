cat << 'PLAN' > plan.md
1. We have replaced multiple occurrences of synchronous disk I/O in Jetpack Compose UI loops (`remember` blocks performing `File.listFiles()` and `gson.fromJson()` when scrolling via `items` in `LazyColumn`) with asynchronous loads off the main thread.
2. The asynchronous loads have been implemented via `produceState` and `withContext(Dispatchers.IO)`.
3. This was done in `JobBrowserScreen.kt`, `AssemblyJobsScreen.kt`, and `HardwoodsJobsScreen.kt`.
4. We verified that tests pass and the build succeeds (`./gradlew test lint`).
5. A Bolt journal entry was logged as requested.
6. A single commit with an appropriate title and description is prepared on branch `bolt-compose-io`.
PLAN
