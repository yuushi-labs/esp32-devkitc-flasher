package com.yuushilabs.esp32flasher.core

/** A version advertised by a GitHub release. */
data class ReleaseVersion(
    val versionName: String,
    val versionCode: Int? = null,
)

/** Input for [UpdateDecision.decide]. */
data class UpdateCheck(
    val currentVersionCode: Int,
    val currentVersionName: String,
    val latest: ReleaseVersion?,
    val notModified: Boolean = false,
    val releaseUrl: String? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val notes: String? = null,
)

sealed class UpdateOutcome {
    /** The installed build is the newest one. */
    data object UpToDate : UpdateOutcome()

    /** The GitHub API answered `304 Not Modified`. */
    data object NotModified : UpdateOutcome()

    /** The repository has no published release. */
    data object NoRelease : UpdateOutcome()

    data class Available(
        val reason: String,
        val latest: ReleaseVersion,
        val releaseUrl: String?,
        val apkUrl: String?,
        val sha256: String?,
        val notes: String?,
    ) : UpdateOutcome()
}

/**
 * Decides whether a published release is newer than the running app.
 *
 * `versionCode` wins when both sides provide one (that is the authoritative Android version);
 * otherwise semantic versions from the release tag are compared.
 */
object UpdateDecision {
    private val VERSION_CODE_PATTERN = Regex("(?i)versioncode\\s*[=:]\\s*(\\d+)")

    fun decide(check: UpdateCheck): UpdateOutcome {
        if (check.notModified) return UpdateOutcome.NotModified
        val latest = check.latest ?: return UpdateOutcome.NoRelease

        val latestCode = latest.versionCode
        if (latestCode != null && check.currentVersionCode > 0) {
            when {
                latestCode > check.currentVersionCode -> return available(check, latest, "versionCode")
                latestCode < check.currentVersionCode -> return UpdateOutcome.UpToDate
            }
        }

        val comparison = compareVersions(latest.versionName, check.currentVersionName)
        return if (comparison > 0) {
            available(check, latest, "versionName")
        } else {
            UpdateOutcome.UpToDate
        }
    }

    private fun available(check: UpdateCheck, latest: ReleaseVersion, reason: String): UpdateOutcome.Available =
        UpdateOutcome.Available(
            reason = reason,
            latest = latest,
            releaseUrl = check.releaseUrl,
            apkUrl = check.apkUrl,
            sha256 = check.sha256,
            notes = check.notes,
        )

    /**
     * Semantic version comparison. Returns a positive number when [left] is newer than [right],
     * negative when older and 0 when equal. A leading `v` is ignored, missing numeric parts count
     * as 0 and prerelease builds sort before the matching release.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = parseVersion(left)
        val b = parseVersion(right)
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1

        val length = maxOf(a.numbers.size, b.numbers.size)
        for (index in 0 until length) {
            val x = a.numbers.getOrElse(index) { 0 }
            val y = b.numbers.getOrElse(index) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        val leftPre = a.prerelease
        val rightPre = b.prerelease
        return when {
            leftPre == null && rightPre == null -> 0
            leftPre == null -> 1
            rightPre == null -> -1
            else -> comparePrerelease(leftPre, rightPre)
        }
    }

    fun parseVersionCode(text: String?): Int? =
        text?.let { VERSION_CODE_PATTERN.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun normalizeVersionName(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    private fun comparePrerelease(left: List<String>, right: List<String>): Int {
        val length = maxOf(left.size, right.size)
        for (index in 0 until length) {
            val x = left.getOrNull(index) ?: return -1
            val y = right.getOrNull(index) ?: return 1
            val xNumber = x.toIntOrNull()
            val yNumber = y.toIntOrNull()
            val result = when {
                xNumber != null && yNumber != null -> xNumber.compareTo(yNumber)
                xNumber != null -> -1
                yNumber != null -> 1
                else -> x.compareTo(y)
            }
            if (result != 0) return if (result > 0) 1 else -1
        }
        return 0
    }

    private data class ParsedVersion(val numbers: List<Int>, val prerelease: List<String>?)

    private fun parseVersion(raw: String): ParsedVersion? {
        val cleaned = normalizeVersionName(raw)
            .substringBefore('+')
            .trim()
        if (cleaned.isEmpty()) return null
        val main = cleaned.substringBefore('-')
        val prerelease = cleaned.substringAfter('-', "").takeIf { it.isNotEmpty() }
        val numbers = main.split('.', '_')
            .map { part ->
                val digits = part.takeWhile { it.isDigit() }
                if (digits.isEmpty()) return null
                digits.toIntOrNull() ?: return null
            }
        if (numbers.isEmpty()) return null
        return ParsedVersion(numbers, prerelease?.split('.', '_')?.filter { it.isNotEmpty() })
    }
}
