package com.paa.assistant.core.hackathon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.paa.assistant.services.RepositoryEntryPoint
import com.paa.assistant.ui.theme.PAATheme
import kotlinx.coroutines.launch

/**
 * Assisted registration: opens the hackathon page in an in-app browser (log in once — cookies are kept),
 * fills form fields from [RegistrationProfile] and highlights them. The owner checks, does any OTP or
 * CAPTCHA and taps the site's own Submit. PAA never submits. A success page marks the task done.
 */
class RegisterActivity : ComponentActivity() {
    private val status = mutableStateOf("Log in if asked, open the registration form, then tap Fill.")
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val profile = RegistrationProfile.load(this)
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            PAATheme {
                Column(Modifier.fillMaxSize()) {
                    Text(status.value, fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                        Button(onClick = { fill(profile) }) { Text("✍️ Fill my details") }
                        OutlinedButton(onClick = { markDone(taskId) }, modifier = Modifier.padding(start = 8.dp)) { Text("✓ I registered") }
                    }
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (profile.isEmpty()) status.value = "Add your details first: PAA → 🧠 card → 🧾 Registration profile."
                                    // Success page? Mark the task done (the owner still pressed Submit themselves)
                                    view.evaluateJavascript("document.body ? document.body.innerText.slice(0, 4000) : ''") { text ->
                                        if (text != null && RegistrationProfile.SUCCESS.containsMatchIn(text)) markDone(taskId)
                                    }
                                }
                            }
                            loadUrl(url)
                            web = this
                        }
                    })
                }
            }
        }
    }

    private fun fill(profile: Map<String, String>) {
        if (profile.isEmpty()) { status.value = "No profile yet: PAA → 🧠 card → 🧾 Registration profile."; return }
        web?.evaluateJavascript(RegistrationProfile.autofillScript(profile)) { n ->
            status.value = "Filled ${n ?: 0} field(s) — check them (purple outline), complete the rest, then press the site's Submit."
        }
    }

    private fun markDone(taskId: Long) {
        if (taskId < 0) { finish(); return }
        lifecycleScope.launch {
            dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, RepositoryEntryPoint::class.java)
                .repository().completeTask(taskId)
            status.value = "✅ Registered — task marked done."
        }
    }

    override fun onBackPressed() {
        if (web?.canGoBack() == true) web?.goBack() else super.onBackPressed()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TASK_ID = "task_id"
        fun intent(context: Context, url: String, taskId: Long) =
            Intent(context, RegisterActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The registration link of a hackathon task: the first link in the message it came from. */
        fun linkOf(sourceMessage: String?): String? =
            sourceMessage?.let { Regex("https?://[^\\s<>\"')]+").find(it)?.value?.trimEnd('.', ',', '!', '?') }
    }
}
