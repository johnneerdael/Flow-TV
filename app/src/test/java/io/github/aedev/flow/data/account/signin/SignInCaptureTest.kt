package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import org.junit.Test

class SignInCaptureTest {
    private val method =
        WebLoginMethod(
            id = "google",
            label = "Sign in",
            startUrl = "https://accounts.google.com/ServiceLogin",
            successUrlPrefix = "https://music.youtube.com",
            cookieUrl = "https://music.youtube.com",
            requiredCookies = listOf("SAPISID"),
            extractScript = "({v: 'x'})",
        )

    @Test
    fun `a sign-in needs every required cookie`() {
        assertThat(SignInCapture.hasRequiredCookies("YSC=x; VISITOR_INFO1_LIVE=y", method)).isFalse()
        assertThat(SignInCapture.hasRequiredCookies("SID=a; SAPISID=b; LOGIN_INFO=c", method)).isTrue()
        assertThat(SignInCapture.hasRequiredCookies(null, method)).isFalse()
    }

    @Test
    fun `only the success prefix completes sign in, not a host that merely starts with it`() {
        assertThat(SignInCapture.isSuccessPage("https://music.youtube.com/", method)).isTrue()
        assertThat(SignInCapture.isSuccessPage("https://music.youtube.com", method)).isTrue()
        assertThat(SignInCapture.isSuccessPage("https://accounts.google.com/v3/signin", method)).isFalse()
        assertThat(SignInCapture.isSuccessPage("https://music.youtube.com.evil.test/", method)).isFalse()
    }

    @Test
    fun `the extraction runs the plugin's script, waits for a promise, and unwraps the values`() {
        val script = SignInCapture.extractionScript(method)
        assertThat(script).contains("return (({v: 'x'}));")
        assertThat(script).contains("Promise.resolve()")
        assertThat(SignInCapture.extractionSettled("null")).isFalse()
        assertThat(SignInCapture.extractionSettled(null)).isFalse()
        assertThat(SignInCapture.extractionSettled("\"{}\"")).isTrue()
        val raw = Json.encodeToString(String.serializer(), """{"visitorData":"Cgt","dataSyncId":"123||x","n":1}""")
        assertThat(SignInCapture.parseExtracted(raw)).containsExactly("visitorData", "Cgt", "dataSyncId", "123||x", "n", "1")
        assertThat(SignInCapture.parseExtracted("null")).isEmpty()
    }

    @Test
    fun `insertText script round-trips hostile text`() {
        val hostile = "p\"a'ss\\w</script>\u2028ö!@#"
        val script = SignInCapture.insertTextScript(hostile)
        val literal = Regex("""\)\((.*)\);$""", RegexOption.DOT_MATCHES_ALL).find(script)!!.groupValues[1]
        assertThat(Json.parseToJsonElement(literal).jsonPrimitive.content).isEqualTo(hostile)
        assertThat(script).contains("document.execCommand('insertText', false, t)")
    }

    @Test
    fun `insertText script focuses a visible text field when none is focused`() {
        val script = SignInCapture.insertTextScript("x")
        assertThat(script).contains("document.activeElement")
        assertThat(script).contains(".focus()")
        assertThat(script).contains("offsetParent")
    }

    @Test
    fun `key presses refocus a visible text field first`() {
        val script = SignInCapture.focusFieldScript()
        assertThat(script).contains("document.activeElement")
        assertThat(script).contains(".focus()")
        assertThat(script).doesNotContain("insertText")
    }

    @Test
    fun `page actions are unwrapped from evaluateJavascript output and capped`() {
        val raw = "\"[\\\"Resend it\\\",\\\"Try another way\\\"]\""
        assertThat(SignInCapture.parsePageActions(raw)).containsExactly("Resend it", "Try another way").inOrder()
        assertThat(SignInCapture.parsePageActions("null")).isEmpty()
        assertThat(SignInCapture.parsePageActions("\"not json\"")).isEmpty()

        val many = (1..20).joinToString(",") { "\\\"b$it\\\"" }
        assertThat(SignInCapture.parsePageActions("\"[$many]\"")).hasSize(MAX_PAGE_ACTIONS)
    }

    @Test
    fun `a click targets only the marked element with that index`() {
        val script = SignInCapture.clickActionScript(4)
        assertThat(script).contains("[data-mv-action=\"4\"]")
        assertThat(SignInCapture.pageActionsScript()).contains("removeAttribute('data-mv-action')")
    }
}
