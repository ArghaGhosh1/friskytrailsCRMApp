package com.crmapplication.utils

import java.util.Locale
import kotlin.random.Random

/**
 * Builds the `public_id` a direct Cloudinary upload is stored under.
 *
 * Kept here — pure, no Android imports — because it must produce the *same* string twice: once to
 * ask the backend to sign it, and again as a form field on the upload itself. Cloudinary signs an
 * exact parameter set, so any drift between the two means "Invalid Signature" rather than a
 * mis-named file.
 *
 * The shape mirrors the web client's `uploadHelper.js` so a file uploaded from the phone lands next
 * to one uploaded from the dashboard: `<sanitized_original_name>_<6 char suffix>[.ext]`.
 *
 * The extension is appended only for non-images. Cloudinary derives an image's extension from the
 * decoded file and appends it to the delivery URL itself, so including it here would produce
 * `photo_a1b2c3.jpg.jpg`. Raw assets like PDFs get no such treatment, so the extension has to be
 * carried in the id or the delivery URL arrives without one.
 */
private val IMAGE_EXTENSIONS = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp")

/** Cloudinary is permissive about ids, but an extension is only worth keeping if it looks like one. */
private val SAFE_EXTENSION = Regex("""^\.[a-z0-9]{1,10}$""")

private const val SUFFIX_LENGTH = 6
private const val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"

/**
 * @param fileName the picked file's display name, or null when the provider didn't supply one.
 * @param suffix injectable purely so tests can assert a fixed id; production callers omit it.
 */
fun cloudinaryPublicId(fileName: String?, suffix: String = randomSuffix()): String {
    val originalName = fileName?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_NAME
    val dotIndex = originalName.lastIndexOf('.')
    val trailingSegment = if (dotIndex != -1) originalName.substring(dotIndex).lowercase(Locale.US) else ""

    // Only split off a trailing segment that actually looks like an extension. A dotted version
    // number ("report.v2 final") isn't one, and splitting there would drop that half of the name
    // without carrying it as an extension either — so it's sanitized as part of the name instead.
    val isExtension = SAFE_EXTENSION.matches(trailingSegment)
    val nameToSanitize = if (isExtension) originalName.substring(0, dotIndex) else originalName

    // Matches the web helper's /[^a-zA-Z0-9-_]/g — deliberately ASCII-only, so a name written in a
    // non-Latin script collapses to underscores rather than reaching Cloudinary unescaped.
    val sanitized = nameToSanitize
        .map { if (it.isIdSafe()) it else '_' }
        .joinToString(separator = "")
        .takeIf { it.any { char -> char != '_' } }
        ?: FALLBACK_NAME

    val keepExtension = isExtension && trailingSegment !in IMAGE_EXTENSIONS
    return sanitized + "_" + suffix + if (keepExtension) trailingSegment else ""
}

fun randomSuffix(): String = buildString(SUFFIX_LENGTH) {
    repeat(SUFFIX_LENGTH) { append(BASE36[Random.nextInt(BASE36.length)]) }
}

private const val FALLBACK_NAME = "file"

private fun Char.isIdSafe(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
