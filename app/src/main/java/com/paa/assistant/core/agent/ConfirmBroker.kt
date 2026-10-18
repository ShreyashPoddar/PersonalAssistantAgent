package com.paa.assistant.core.agent

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows [ConfirmActivity] for one action and waits for the owner's answer. The action stays here (never in Intent
 * extras); the activity looks it up by request id.
 */
@Singleton
class ConfirmBroker @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val gate: BiometricGate
) {
    class Pending(val action: Action, val source: CommandSource, val biometric: Boolean) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun ask(action: Action, source: CommandSource, biometric: Boolean): Boolean {
        val id = UUID.randomUUID().toString()
        val p = Pending(action, source, biometric)
        pending[id] = p
        try {
            ctx.startActivity(
                Intent(ctx, ConfirmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ConfirmActivity.EXTRA_REQUEST_ID, id)
            )
            return withTimeoutOrNull(TIMEOUT_MS) { p.answer.await() } ?: false
        } finally {
            pending.remove(id)
        }
    }

    fun get(requestId: String): Pending? = pending[requestId]

    /** For biometric requests, approval counts only with a valid signature over this exact action. */
    fun complete(requestId: String, approved: Boolean, signature: ByteArray?) {
        val p = pending.remove(requestId) ?: return
        val ok = approved && (!p.biometric || (signature != null && gate.verify(p.action, requestId, signature)))
        p.answer.complete(ok)
    }

    companion object {
        private const val TIMEOUT_MS = 60_000L
    }
}
