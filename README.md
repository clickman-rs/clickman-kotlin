# ClickMan for Kotlin

ClickMan records analytics events locally and sends them to a ClickMan server.
Events wait in a SQLite queue, so an app restart or a lost connection does not
discard them. The client sends gzip batches and retries failed requests.

The repository contains two libraries:

| Library | Use it for |
| --- | --- |
| `clickman` | JVM applications. You provide an AndroidX SQLite driver. |
| `clickman-android` | Android apps. Adds OS SQLite, app context, lifecycle events, and connectivity handling. |

## Add the library

Build with JDK 17. Android builds also need Android SDK 36; the Android library
supports API 28 and later.

To try a local checkout, publish its artifacts to your local Maven repository:

```sh
./gradlew :clickman:publishToMavenLocal
# For Android, publish both artifacts:
./gradlew -Pandroid=true :clickman:publishToMavenLocal :clickman-android:publishToMavenLocal
```

Add `mavenLocal()` to your application's dependency repositories, then choose
one dependency:

```kotlin
// Android:
implementation("com.lockvoid.clickman:clickman-android:0.1.0")
// JVM:
implementation("com.lockvoid.clickman:clickman:0.1.0")
```

## Start on Android

Call `start` once on the main thread, usually from `Application.onCreate`, and
keep the returned client for the lifetime of the application:

```kotlin
import com.lockvoid.clickman.android.ClickManAndroid

val analytics = ClickManAndroid.start(
    context = applicationContext,
    endpoint = "https://analytics.example.com",
    writeKey = "your-project-write-key",
)
analytics.identify("user_42")
analytics.setTraits(mapOf("plan" to "pro"))
analytics.track("page_viewed", mapOf("screen" to "home"))
```

The Android integration stores its queue in the app's no-backup directory and
tracks app installation, updates, foreground entry, and background entry. Pass
`tracksLifecycle = false` to disable those events.

## Start on the JVM

Choose a SQLite driver and a writable file for the queue. This example uses
`androidx.sqlite:sqlite-bundled`; add that dependency to your application.

```kotlin
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.lockvoid.clickman.ClickMan
import java.io.File

val analytics = ClickMan(ClickMan.Configuration(
    endpoint = "https://analytics.example.com",
    writeKey = "your-project-write-key",
    storage = File("data/clickman/queue.sqlite"),
    driver = BundledSQLiteDriver(),
))
analytics.track("page_viewed", mapOf("screen" to "home"))
// Close the client when the application shuts down.
```

Both libraries send in the background. By default, a batch becomes due after
20 queued events or 30 seconds, and the queue holds at most 10,000 events.
Configure these values through `ClickMan.Configuration` or Android's `configure`
callback. `flushInBackground()` requests an immediate send; `flush()` waits for
the attempt and belongs off the UI thread. A failed send still observes its
retry delay. Call `reset()` after sign-out to clear the current identity and traits.
Flushes and scheduled sends remove events older than 30 days even while a longer
retry delay is still waiting.

Use the server's base URL as `endpoint`: the client appends `/v1/batch`. If the
server is mounted at `/analytics`, use `https://example.com/analytics`.
The endpoint must be an absolute HTTP(S) URL without credentials, query or
fragment. Source keys must contain visible ASCII characters without whitespace.
Configuration is checked before opening the queue; limits and intervals must be
positive and finite. Keep a separate queue file for each endpoint and source key.

Properties, traits and context reject cycles, non-finite numbers and nesting
beyond 64 levels. Failed conversions are logged and do not store an event or
replace existing traits/context.

## Develop and test

```sh
./gradlew :clickman:test
./gradlew -Pandroid=true :clickman-android:testDebugUnitTest
python3 scripts/queue-schema.py --check
```

The [protocol](docs/PROTOCOL.md) describes event fields, delivery, retries, and
queue behavior. Its [local snapshot](protocol/README.md) includes the fixtures
used by the tests; no server checkout is required. `e2e-worker/` is a command-line
driver for integration tests.

## License

[MIT](LICENSE).
