package io.github.aedev.flow.plugin

import android.webkit.WebView
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhonePointerAction
import io.github.aedev.flow.ui.components.shared.FlowWebViewStream
import io.github.aedev.flow.ui.tv.screens.account.LoginWebViewController
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class RemoteLoginViewTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: LoginWebViewController

    private fun evaluate(script: String): String? {
        val answer = CompletableDeferred<String?>()
        compose.runOnIdle { controller.webView.evaluateJavascript(script) { answer.complete(it) } }
        return runBlocking { withTimeout(5_000) { answer.await() } }
    }

    @Test
    fun spotifyPageUsesThePhoneViewportAndProducesAVisibleFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pack =
            instrumentation.context.assets
                .open("spotify.mbplugin")
                .use(io.github.aedev.flow.plugin.pkg.PluginPackageReader::read)
        val method =
            pack.manifest.signIn
                .filterIsInstance<WebLoginMethod>()
                .first()
        compose.setContent {
            val activity = LocalActivity.current!!
            TvTheme {
                Surface {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val login =
                        androidx.compose.runtime.remember {
                            LoginWebViewController(context, {}, method, {}, activity.window).also { controller = it }
                        }
                    val dimensions = LocalTvDimens.current
                    FlowWebViewStream(login.stream, DpSize(dimensions.signInViewportWidth, dimensions.signInViewportHeight))
                }
            }
        }
        try {
            compose.waitUntil(30_000) {
                evaluate("document.querySelector('main')?.getBoundingClientRect().height>200 && document.body.innerText.length>100") ==
                    "true"
            }
            assertEquals("360", evaluate("innerWidth"))
            val frame = runBlocking { controller.frame() }
            assertNotNull(frame)
            assertTrue(frame!!.height > frame.width)
            File(instrumentation.targetContext.cacheDir, "spotify-login-preview.jpg").writeBytes(Base64.getDecoder().decode(frame.jpeg))
        } finally {
            compose.runOnIdle { controller.destroy() }
        }
    }

    @Test
    fun viewportAndNativePointerReachASandboxedIframe() {
        val method =
            WebLoginMethod(
                id = "fixture",
                label = "Fixture",
                startUrl = "about:blank",
                successUrlPrefix = "https://signed-in.example/",
                cookieUrl = "https://login.example/",
                requiredCookies = listOf("session"),
                pageScript =
                    """
                    var m=document.querySelector('main');if(m){
                        m.style.position='static';m.style.height='auto';m.style.overflow='visible'
                    }
                    """.trimIndent(),
            )
        compose.setContent {
            val activity = LocalActivity.current!!
            TvTheme {
                Surface {
                    AndroidView(factory = { context ->
                        LoginWebViewController(context, {}, method, {}, activity.window).also { controller = it }.webView
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        try {
            compose.runOnIdle {
                controller.webView.loadDataWithBaseURL("https://login.example/", FIXTURE_HTML, "text/html", "UTF-8", null)
            }
            compose.waitUntil(20_000) { evaluate("document.querySelector('main')?.getBoundingClientRect().height>700") == "true" }
            val visual = CompletableDeferred<Unit>()
            compose.runOnIdle {
                controller.webView.postVisualStateCallback(
                    1,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            visual.complete(Unit)
                        }
                    },
                )
            }
            runBlocking { withTimeout(5_000) { visual.await() } }
            compose.waitForIdle()
            val coordinates =
                evaluate(
                    """
                    JSON.stringify((function(){
                        var r=document.querySelector('iframe').getBoundingClientRect();
                        return {x:(r.left+50)/innerWidth,y:(r.top+50)/innerHeight}
                    })())
                    """.trimIndent(),
                )
            val point = JSONObject(JSONObject("{\"result\":$coordinates}").getString("result"))
            val frame = runBlocking { controller.frame() }
            assertNotNull(frame)
            val bytes = Base64.getDecoder().decode(frame!!.jpeg)
            assertTrue(frame.width <= 960 && frame.height <= 960)
            assertEquals(0xff, bytes[0].toInt() and 255)
            assertEquals(0xd8, bytes[1].toInt() and 255)
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "remote-login-fixture.jpg").writeBytes(bytes)
            val geometry =
                evaluate(
                    """
                    JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio,
                    rect:document.querySelector('iframe').getBoundingClientRect().toJSON()})
                    """.trimIndent(),
                )
            println("Remote fixture geometry: $geometry")
            compose.runOnIdle {
                println(
                    "Remote native=${controller.webView.width}x${controller.webView.height}, scale=${controller.webView.scale}, point=$point",
                )
            }
            compose.runOnIdle {
                val x = point.getDouble("x").toFloat()
                val y = point.getDouble("y").toFloat()
                controller.pointer(PhoneInput.Pointer(PhonePointerAction.DOWN, x, y))
                controller.pointer(PhoneInput.Pointer(PhonePointerAction.UP, x, y))
            }
            println(
                "Remote fixture result: ${evaluate(
                    "JSON.stringify({clicked:document.body.dataset.clicked,trusted:document.body.dataset.trusted})",
                )}",
            )
            compose.waitUntil(5_000) { evaluate("document.body.dataset.clicked==='yes'") == "true" }
            assertEquals("\"true\"", evaluate("document.body.dataset.trusted"))
            val editorCoordinates =
                evaluate(
                    """
                    JSON.stringify((function(){var r=document.querySelector('iframe').getBoundingClientRect();
                    return {x:(r.left+50)/innerWidth,y:(r.top+135)/innerHeight}})())
                    """.trimIndent(),
                )
            val editor = JSONObject(JSONObject("{\"result\":$editorCoordinates}").getString("result"))
            compose.runOnIdle {
                val x = editor.getDouble("x").toFloat()
                val y = editor.getDouble("y").toFloat()
                controller.pointer(PhoneInput.Pointer(PhonePointerAction.DOWN, x, y))
                controller.pointer(PhoneInput.Pointer(PhonePointerAction.UP, x, y))
            }
            compose.waitUntil(5_000) { evaluate("document.body.dataset.focused==='yes'") == "true" }
            runBlocking { withContext(Dispatchers.Main) { controller.typeText("hello", null) } }
            compose.waitUntil(5_000) { evaluate("document.body.dataset.typed==='hello'") == "true" }
            compose.runOnIdle { controller.visible(false) }
            assertNull(runBlocking { controller.frame() })
        } finally {
            compose.runOnIdle { controller.destroy() }
        }
    }
}

private const val FIXTURE_HTML = """<!doctype html><html><body style="margin:0;background:white">
<main style="position:absolute;height:48px;overflow:hidden"><section style="height:729px">
<iframe sandbox="allow-scripts" style="width:200px;height:200px;margin:40px;border:0" srcdoc="<body style='margin:0'><input type='checkbox' style='width:100px;height:100px;margin:0' onchange='parent.postMessage({clicked:this.checked,trusted:event.isTrusted}, &quot;*&quot;)'><input style='display:block;width:180px;height:30px;margin-top:20px' onfocus='parent.postMessage({focused:true}, &quot;*&quot;)' oninput='parent.postMessage({typed:this.value}, &quot;*&quot;)'></body>"></iframe>
</section></main><script>addEventListener('message',function(e){
if(typeof e.data.clicked==='boolean'){document.body.dataset.clicked=e.data.clicked?'yes':'no';document.body.dataset.trusted=String(e.data.trusted)}
if(e.data.focused)document.body.dataset.focused='yes';if(e.data.typed)document.body.dataset.typed=e.data.typed
})</script></body></html>"""
