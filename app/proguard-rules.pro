# SPDX-FileCopyrightText: 2025 Flow
# SPDX-License-Identifier: GPL-3.0-or-later
# Based on NewPipe's ProGuard configuration

# https://developer.android.com/build/shrink-code

## Open-source readable stack traces (no de-obfuscation pipeline required)
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

## Strip debug and verbose logging from release binaries
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

## NewPipeExtractor: the video player only uses its stream model and DASH manifest creator, which
## it calls directly, so nothing of it needs keeping; its extraction stack (Rhino) is unused.
-keepattributes Exceptions, InnerClasses
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

## Rules for Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

## Keep data models and serialization structures
-keep class io.github.aedev.flow.data.model.** { *; }
-keep class io.github.aedev.flow.data.local.** { *; }
-keep class io.github.aedev.flow.data.lyrics.** { *; }

## Gson-persisted models that live OUTSIDE the packages above (issue #996): without an
## explicit keep, R8 may strip the generic Signature of MusicTrack.artists, and cached
## history/queues then deserialize artist entries as LinkedTreeMaps that crash the music
## feed with a ClassCastException on first access. Any new Gson-persisted model must be
## added here (or live in a kept package), whatever package it renders from.
-keep class io.github.aedev.flow.ui.screens.music.MusicTrack { *; }
-keep class io.github.aedev.flow.ui.screens.music.MusicArtist { *; }
-keep class io.github.aedev.flow.ui.screens.music.MusicItemType { *; }
-keep class io.github.aedev.flow.data.music.Playlist { *; }
-keep class io.github.aedev.flow.data.music.DownloadedTrack { *; }
-keep class io.github.aedev.flow.data.music.DownloadStatus { *; }

## Rules for Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

## Optional classes other libraries reference
-dontwarn org.conscrypt.**
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.helper.Re2jRegex
-dontwarn org.jsoup.helper.Re2jRegex$Re2jMatcher

## Third-party / Platform warning suppressions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn java.beans.**