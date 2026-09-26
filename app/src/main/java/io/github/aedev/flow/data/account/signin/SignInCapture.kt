package io.github.aedev.flow.data.account.signin

import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.innertube.utils.parseCookieString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SignInCapture {
    private const val MUSIC_ORIGIN = "https://music.youtube.com/"

    fun hasSession(cookie: String?): Boolean = cookie != null && "SAPISID" in parseCookieString(cookie)

    fun isYouTubeMusic(url: String?): Boolean = url != null && (url == MUSIC_ORIGIN.dropLast(1) || url.startsWith(MUSIC_ORIGIN))

    fun parseYtcfg(raw: String?): Pair<String?, String?> {
        val inner = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.contentOrNull }.getOrNull() ?: return null to null
        val obj = runCatching { Json.parseToJsonElement(inner).jsonObject }.getOrNull() ?: return null to null
        return obj["v"]?.jsonPrimitive?.contentOrNull to obj["d"]?.jsonPrimitive?.contentOrNull
    }

    fun session(
        cookie: String,
        visitorData: String?,
        rawDataSyncId: String?,
    ): AccountSession =
        AccountSession(
            cookie = cookie,
            visitorData = visitorData?.takeIf { it.isNotBlank() },
            dataSyncId = rawDataSyncId?.takeIf { it.isNotBlank() }?.substringBefore("||"),
        )

    fun insertTextScript(text: String): String =
        "(function(t){$FOCUS_FIELD return document.execCommand('insertText', false, t);})(${JsonPrimitive(text)});"

    fun focusFieldScript(): String = "(function(){$FOCUS_FIELD})();"

    // Google's pages do not always autofocus their field, and hiding the TV keyboard blurs it again,
    // so typed text and key presses would otherwise land on the page body.
    private const val FOCUS_FIELD =
        "var a=document.activeElement;" +
            "if(!a||(a.tagName!=='INPUT'&&a.tagName!=='TEXTAREA')){" +
            "a=Array.prototype.find.call(document.querySelectorAll(" +
            "'input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit]),textarea')," +
            "function(e){return e.offsetParent!==null;});" +
            "if(a){a.focus();}" +
            "}"
}
