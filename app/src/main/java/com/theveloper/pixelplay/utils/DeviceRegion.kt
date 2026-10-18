package com.theveloper.pixelplay.utils

import java.util.Locale

private val ISO_COUNTRIES: Set<String> = Locale.getISOCountries().toHashSet()

/** True when [code] is an ISO-3166 alpha-2 country code Innertube will accept in `client.gl`. */
fun isIsoCountry(code: String?): Boolean = !code.isNullOrBlank() && code.uppercase() in ISO_COUNTRIES

/**
 * Resolves the client region used for every YouTube Music request.
 *
 * Innertube requires an ISO-3166 alpha-2 country code in `client.gl`. The UI offers "Global" as a
 * display-only choice, and an unset preference is blank; both used to collapse straight to "IN",
 * which served Indian charts, trending, new releases and mood stations to every user regardless of
 * where they actually live. The device's own region is a far better default. "IN" is retained only
 * as a last resort for platforms that report no usable country.
 */
fun resolveRequestRegion(configured: String?): String {
    val candidate = configured?.trim()?.uppercase().orEmpty()
    if (candidate in ISO_COUNTRIES) return candidate

    val deviceCountry = Locale.getDefault().country.uppercase()
    return if (deviceCountry in ISO_COUNTRIES) deviceCountry else "IN"
}
