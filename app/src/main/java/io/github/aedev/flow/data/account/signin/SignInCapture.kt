package io.github.aedev.flow.data.account.signin

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.WebLoginMethod

internal object SignInCapture {
    private const val RESULT_SLOT = "__mbSignInResult"
    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * Whether [url] is under the method's success prefix, and not a host that merely starts with it. The
     * sign-in's own start page never is, so a site that signs in on its own domain can name that domain.
     */
    fun isSuccessPage(
        url: String?,
        method: WebLoginMethod,
    ): Boolean {
        val prefix = method.successUrlPrefix
        if (url == null || !url.startsWith(prefix)) return false
        if (pageOf(url) == pageOf(method.startUrl)) return false
        return url.length == prefix.length || prefix.endsWith("/") || url[prefix.length] in "/?#"
    }

    private fun pageOf(url: String): String = url.substringBefore('#').substringBefore('?')

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

    /**
     * Marks the page's visible text fields with their index and returns their labels and whether each
     * takes a password, so the phone can offer one box per field of a form that asks for several at once.
     */
    fun pageFieldsScript(): String =
        "(function(){$FIELDS" +
            "var o=document.querySelectorAll('[data-mv-field]');" +
            "for(var i=0;i<o.length;i++){o[i].removeAttribute('data-mv-field');}" +
            "var out=[],l=fields();" +
            "for(var j=0;j<l.length&&out.length<$MAX_PAGE_FIELDS;j++){" +
            "var el=l[j];" +
            "var t=(el.labels&&el.labels.length?el.labels[0].innerText:" +
            "(el.getAttribute('aria-label')||el.placeholder||el.name||el.type||'')).replace(/\\s+/g,' ').trim().slice(0,40);" +
            "el.setAttribute('data-mv-field',out.length);out.push({label:t,secret:el.type==='password'});" +
            "}" +
            "return JSON.stringify(out);" +
            "})();"

    fun parsePageFields(raw: String?): List<PhoneField> {
        val inner = runCatching { Json.parseToJsonElement(raw.orEmpty()).jsonPrimitive.contentOrNull }.getOrNull() ?: return emptyList()
        return runCatching { lenient.decodeFromString(ListSerializer(PhoneField.serializer()), inner) }
            .getOrDefault(emptyList())
            .take(MAX_PAGE_FIELDS)
    }

    /**
     * Types [text] into the page: into the [field]th field the phone was offered, replacing what it held,
     * or, without one, into the focused field.
     */
    fun insertTextScript(
        text: String,
        field: Int? = null,
    ): String {
        val target =
            field
                ?.let {
                    "var f=document.querySelector('[data-mv-field=\"$it\"]');if(f){f.focus();window.__mbField=f;f.select();}"
                }.orEmpty()
        return "(function(t){$target$FOCUS_FIELD return document.execCommand('insertText', false, t);})(${JsonPrimitive(text)});"
    }

    fun focusFieldScript(): String = "(function(){$FOCUS_FIELD})();"

    /** Moves to the page's next text field, answering whether there was one; the remote's Tab cannot. */
    fun nextFieldScript(): String =
        "(function(){$FOCUS_FIELD var l=fields(),n=l[l.indexOf(a)+1];if(n){n.focus();window.__mbField=n;}return !!n;})();"

    fun movedToNextField(raw: String?): Boolean = raw == "true"

    // Pages do not always autofocus their field, and hiding the TV keyboard blurs it again, so typed
    // text and key presses would otherwise land on the page body. The field last focused is taken back
    // (a page with a username and a password on one form would otherwise always get the first), and the
    // first visible field otherwise.
    private const val FIELDS =
        "var fields=function(){return Array.prototype.filter.call(document.querySelectorAll(" +
            "'input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit]):not([type=button]),textarea')," +
            "function(e){return e.offsetParent!==null;});};"

    private const val FOCUS_FIELD =
        FIELDS +
            "if(!window.__mbFieldWatch){window.__mbFieldWatch=1;document.addEventListener('focusin',function(e){" +
            "var t=e.target;if(t&&(t.tagName==='INPUT'||t.tagName==='TEXTAREA')){window.__mbField=t;}},true);}" +
            "var a=document.activeElement;" +
            "if(!a||(a.tagName!=='INPUT'&&a.tagName!=='TEXTAREA')){" +
            "var last=window.__mbField;" +
            "a=last&&last.isConnected&&last.offsetParent!==null?last:fields()[0];" +
            "if(a){a.focus();}" +
            "}" +
            "if(a){window.__mbField=a;}"
}
