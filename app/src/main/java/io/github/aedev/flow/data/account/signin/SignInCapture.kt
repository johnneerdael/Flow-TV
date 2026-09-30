package io.github.aedev.flow.data.account.signin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.WebLoginMethod

internal object SignInCapture {
    private const val RESULT_SLOT = "__mbSignInResult"

    /** Whether [url] is under the method's success prefix, and not a host that merely starts with it. */
    fun isSuccessPage(
        url: String?,
        method: WebLoginMethod,
    ): Boolean {
        val prefix = method.successUrlPrefix
        if (url == null || !url.startsWith(prefix)) return false
        return url.length == prefix.length || prefix.endsWith("/") || url[prefix.length] in "/?#"
    }

    fun hasRequiredCookies(
        cookie: String?,
        method: WebLoginMethod,
    ): Boolean {
        val names =
            cookie
                ?.split(';')
                .orEmpty()
                .map { it.substringBefore('=').trim() }
                .toSet()
        return cookie != null && method.requiredCookies.all { it in names }
    }

    /**
     * Starts the plugin's extraction script, which may evaluate to a promise; its settled value is left on
     * the page as JSON text for [extractionResultScript] to collect, since `evaluateJavascript` cannot
     * wait for a promise.
     */
    fun extractionScript(method: WebLoginMethod): String =
        "(function(){window.$RESULT_SLOT=undefined;" +
            "Promise.resolve().then(function(){return (${method.extractScript ?: "{}"});})" +
            ".then(function(v){window.$RESULT_SLOT=JSON.stringify(v);}," +
            "function(){window.$RESULT_SLOT='{}';});})();"

    /** The extraction's JSON text once it has settled, else `null`. */
    fun extractionResultScript(): String = "window.$RESULT_SLOT===undefined?null:window.$RESULT_SLOT"

    /** Whether `evaluateJavascript` output of [extractionResultScript] means the extraction has settled. */
    fun extractionSettled(raw: String?): Boolean = raw != null && raw != "null"

    /** The extracted values from `evaluateJavascript` output, which wraps the JSON text in a string. */
    fun parseExtracted(raw: String?): Map<String, String> {
        val inner = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.contentOrNull }.getOrNull() ?: return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(inner).jsonObject }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap()
    }

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
