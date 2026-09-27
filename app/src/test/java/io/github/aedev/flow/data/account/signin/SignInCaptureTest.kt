package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SignInCaptureTest {
    @Test
    fun `a session needs SAPISID`() {
        assertThat(SignInCapture.hasSession("YSC=x; VISITOR_INFO1_LIVE=y")).isFalse()
        assertThat(SignInCapture.hasSession("SID=a; SAPISID=b; LOGIN_INFO=c")).isTrue()
        assertThat(SignInCapture.hasSession(null)).isFalse()
    }

    @Test
    fun `only music youtube completes sign in`() {
        assertThat(SignInCapture.isYouTubeMusic("https://music.youtube.com/")).isTrue()
        assertThat(SignInCapture.isYouTubeMusic("https://accounts.google.com/v3/signin")).isFalse()
        assertThat(SignInCapture.isYouTubeMusic("https://music.youtube.com.evil.test/")).isFalse()
    }

    @Test
    fun `ytcfg values are unwrapped from evaluateJavascript output`() {
        val raw = "\"{\\\"v\\\":\\\"CgtABC\\\",\\\"d\\\":\\\"123||456\\\"}\""
        assertThat(SignInCapture.parseYtcfg(raw)).isEqualTo("CgtABC" to "123||456")
        assertThat(SignInCapture.parseYtcfg("null")).isEqualTo(null to null)
    }

    @Test
    fun `the data sync id keeps only the account part`() {
        val session = SignInCapture.session("SAPISID=b", "CgtABC", "123||456")
        assertThat(session.dataSyncId).isEqualTo("123")
        assertThat(session.visitorData).isEqualTo("CgtABC")
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
