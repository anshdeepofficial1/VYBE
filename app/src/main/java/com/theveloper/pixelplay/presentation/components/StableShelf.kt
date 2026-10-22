package com.theveloper.pixelplay.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Holds a shelf's content still once it has settled, while still accepting a deliberate change.
 *
 * Home and Explore are assembled from several asynchronous passes: local history, the persisted
 * mix, cache, radio and finally the provider Home response. Each pass used to replace the row a
 * moment after it had already been drawn, so a card could be swapped out from under a finger that
 * was already on it.
 *
 * The rows are therefore latched: the first result that stays unchanged for [settleMillis] is kept
 * for the lifetime of this [generation]. Bumping the generation - an explicit refresh, or the app
 * returning to the foreground - releases the latch so the next settled result is adopted.
 *
 * Two rules keep the latch from freezing an incomplete row:
 *  - a later result that only appends to what is already shown is always adopted, so a shelf that
 *    arrived in two passes does not stay stuck on the first half;
 *  - nothing is latched until the data has actually stopped changing, which is what "settled" means.
 */
@Composable
fun <T> rememberStableShelf(
    items: List<T>,
    generation: Int = 0,
    settleMillis: Long = 800L,
): List<T> {
    var committed by remember(generation) { mutableStateOf<List<T>?>(null) }
    val latest by rememberUpdatedState(items)

    LaunchedEffect(generation, items) {
        if (items.isEmpty()) return@LaunchedEffect
        val current = committed
        if (current != null) {
            // The row was drawn from a partial pass and the rest has now arrived. Growing in place
            // keeps every visible position where the listener saw it, which is the part that must
            // not move - they explicitly must not be reordered.
            if (items.size > current.size && items.subList(0, current.size) == current) {
                committed = items
            }
            return@LaunchedEffect
        }
        // Every new emission restarts this effect, so the delay only elapses once the data has
        // actually stopped arriving.
        delay(settleMillis)
        if (committed == null) committed = items
    }

    return committed ?: latest
}
