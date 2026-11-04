
[![build status](https://github.com/nikossvnk/ical4android/actions/workflows/check.yml/badge.svg)](https://github.com/nikossvnk/ical4android/actions/workflows/check.yml)


# ical4android

ical4android is a library for Android that brings together iCalendar and Android.
It's a framework for

* parsing and generating iCalendar resources (using [ical4j](https://github.com/ical4j/ical4j))
  from/into data classes that are compatible with the Android Calendar Provider and
  third-party task providers,
* accessing the Android Calendar Provider (and third-party task providers) over a unified API.

It has been primarily developed for:

* [DAVx⁵](https://www.davx5.com)
* [ICSx⁵](https://icsx5.bitfire.at)

_This software is not affiliated to, nor has it been authorized, sponsored or otherwise approved
by Google LLC. Android is a trademark of Google LLC._

Generated KDoc: https://bitfireAT.gitlab.io/ical4android/dokka/ical4android/

Discussion: https://forums.bitfire.at/category/18/libraries

## How to use

This maintained fork is consumed as a source submodule by
[`etesync-android`](https://github.com/nikossvnk/etesync-android). Include
`:ical4android` in your Gradle settings and use
`implementation(project(":ical4android"))`. Repositories belong in the consuming
project's settings (Google and Maven Central); no JitPack repository is required.

## Development and API migration

Use JDK 25, the included Gradle 9.8.2 wrapper, and Android SDK Platform 37.0.
The module uses AGP 9.5.0, built-in Kotlin 2.4.21, Java 17 bytecode, minSdk 23,
and core-library desugaring.

```sh
./gradlew build compileDebugAndroidTestKotlin
./gradlew connectedCheck
```

The ical4j 4.4.0 migration replaces `Date`/`DateTime` with `java.time` temporals.
Keep all-day `LocalDate` values distinct from instants/zoned date-times.
Property/component lookups return typed `Optional` values; retain the returned
value when modifying immutable-style property and parameter lists.
`util/TimeApiExtensions.kt` centralizes temporal conversion and duration handling.

JUnit4 JVM tests cover parsing, serialization, recurrences, timezones and alarms.
Instrumented tests exercise Android providers and require contacts/calendar
permissions. Task-provider cases need Tasks.org/OpenTasks. CI builds, lints,
runs JVM tests, and compiles instrumentation; it does not execute device tests.
Dependabot and scheduled CI run weekly.



## Contact

```
bitfire web engineering – Stockmann, Hirner GesnbR
Florastraße 27
2540 Bad Vöslau, AUSTRIA
```

Email: [play@bitfire.at](mailto:play@bitfire.at) (do not use this)


## License 

Copyright (C) bitfire web engineering (Ricki Hirner, Bernhard Stockmann) and contributors.

This program comes with ABSOLUTELY NO WARRANTY. This is free software, and you are welcome
to redistribute it under the conditions of the [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html).

