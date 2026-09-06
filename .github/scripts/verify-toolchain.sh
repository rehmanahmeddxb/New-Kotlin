#!/usr/bin/env bash
# Fail CI when pinned toolchain.env versions drift from Gradle files.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [[ ! -f toolchain.env ]]; then
  echo "error: toolchain.env is missing" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a
source toolchain.env
set +a

fail=0
check() {
  local label="$1" file="$2" needle="$3"
  if [[ ! -f "$file" ]]; then
    echo "error: $file is missing (needed for $label)" >&2
    fail=1
    return
  fi
  if ! grep -q -- "$needle" "$file"; then
    echo "error: $label mismatch — expected '$needle' in $file" >&2
    fail=1
  else
    echo "ok: $label ($needle)"
  fi
}

check "Java target (app)" "app/build.gradle.kts" "VERSION_${JAVA_VERSION}"
check "Java target (app jvm)" "app/build.gradle.kts" "jvmTarget = \"${JAVA_VERSION}\""
check "compileSdk" "app/build.gradle.kts" "compileSdk = ${COMPILE_SDK}"
check "minSdk" "app/build.gradle.kts" "minSdk = ${MIN_SDK}"
check "targetSdk" "app/build.gradle.kts" "targetSdk = ${TARGET_SDK}"
check "Android Gradle Plugin" "build.gradle.kts" "com.android.application\") version \"${AGP_VERSION}\""
check "Kotlin Android plugin" "build.gradle.kts" "org.jetbrains.kotlin.android\") version \"${KOTLIN_VERSION}\""
check "Gradle wrapper" "gradle/wrapper/gradle-wrapper.properties" "gradle-${GRADLE_VERSION}-bin.zip"
check "CI uses toolchain Java" ".github/workflows/android-ci.yml" "env.JAVA_VERSION"

if [[ ! -x ./gradlew ]]; then
  echo "error: gradlew is missing or not executable" >&2
  fail=1
else
  echo "ok: gradlew is executable"
fi

if [[ ! -f gradle/wrapper/gradle-wrapper.jar ]]; then
  echo "error: gradle-wrapper.jar is missing" >&2
  fail=1
else
  echo "ok: gradle-wrapper.jar"
fi

if [[ "$fail" -ne 0 ]]; then
  echo "toolchain verification failed" >&2
  exit 1
fi

echo "toolchain verification passed"
