package io.github.aedev.flow.data.account.signin

import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.innertube.utils.parseCookieString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

    /**
     * Marks the page's visible buttons, links and checkboxes (footer links aside) with their index and
     * returns their labels, so the phone can click what the remote cannot reach on Google's pages.
     */
    fun pageActionsScript(): String =
        "(function(){" +
            "var o=document.querySelectorAll('[data-mv-action]');" +
            "for(var i=0;i<o.length;i++){o[i].removeAttribute('data-mv-action');}" +
            "var e=document.querySelectorAll('a[href],button,[role=button],[role=link],[role=checkbox]," +
            "input[type=submit],input[type=button],input[type=checkbox]');" +
            "var out=[],seen={};" +
            "for(var j=0;j<e.length&&out.length<$MAX_PAGE_ACTIONS;j++){" +
            "var el=e[j];" +
            "if(el.offsetParent===null||el.disabled||el.closest('footer,[aria-hidden=true]'))continue;" +
            "var l=(el.labels&&el.labels.length?el.labels[0].innerText:" +
            "(el.innerText||el.getAttribute('aria-label')||el.value||'')).replace(/\\s+/g,' ').trim();" +
            "if(!l||l.length>60||seen[l])continue;" +
            "seen[l]=1;el.setAttribute('data-mv-action',out.length);out.push(l);" +
            "}" +
            "return JSON.stringify(out);" +
            "})();"

    fun clickActionScript(index: Int): String =
        "(function(){var e=document.querySelector('[data-mv-action=\"$index\"]');if(e){e.click();}})();"

    fun parsePageActions(raw: String?): List<String> {
        val inner = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.contentOrNull }.getOrNull() ?: return emptyList()
        return runCatching { Json.parseToJsonElement(inner).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
            .getOrDefault(emptyList())
            .take(MAX_PAGE_ACTIONS)
    }

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
