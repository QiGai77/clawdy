package io.github.nanomuse

import org.json.JSONObject

/**
 * The string under [key], or null when the key is missing or holds a JSON `null`.
 *
 * `JSONObject.optString(key, null)` is the usual way to spell this, but on Android it hands back
 * the *text* "null" for a JSON `null` (the value is `JSONObject.NULL`, and the fallback is only
 * used for an absent key), and Kotlin flags the null fallback at every call. Model output and
 * server JSON do carry explicit nulls, so the places that read optional strings go through this.
 */
fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
