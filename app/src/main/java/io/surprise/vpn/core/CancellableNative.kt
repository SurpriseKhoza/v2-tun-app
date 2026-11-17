package io.surprise.vpn.core

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking native call (libXray / gomobile) on a dedicated daemon thread and
 * suspends until it finishes. Unlike a plain withContext(Dispatchers.IO) block, the
 * caller is released immediately when its coroutine is cancelled, so the tunnel mutex
 * is never held hostage by a slow SSH handshake.
 *
 * If the caller was cancelled before the native call finished, a successful result is
 * handed to [onAbandoned] so the resource it created can be released.
 */
internal suspend fun <T> runNativeCancellable(
    name: String,
    onAbandoned: (T) -> Unit,
    block: () -> T
): T = suspendCancellableCoroutine { continuation ->
    val claimed = AtomicBoolean(false)
    continuation.invokeOnCancellation { claimed.set(true) }
    val worker = Thread({
        val result = runCatching(block)
        if (claimed.compareAndSet(false, true) && continuation.isActive) {
            continuation.resumeWith(result)
        } else {
            result.onSuccess { runCatching { onAbandoned(it) } }
        }
    }, "v2tun-native-$name")
    worker.isDaemon = true
    worker.start()
}
