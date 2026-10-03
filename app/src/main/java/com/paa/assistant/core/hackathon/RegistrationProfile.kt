package com.paa.assistant.core.hackathon

import android.content.Context
import com.paa.assistant.data.db.DatabaseEncryption
import org.json.JSONObject

/**
 * The owner's details for hackathon/event registration forms, stored encrypted (Keystore key).
 * Used only to fill forms the owner opens and reviews in [RegisterActivity] — never sent anywhere by PAA.
 */
object RegistrationProfile {
    private const val PREFS = "paa_reg_profile"
    private const val KEY = "profile"

    /** Field key → label shown in the editor. Keys are what the autofill script matches on. */
    val FIELDS = linkedMapOf(
        "fullName" to "Full name", "firstName" to "First name", "lastName" to "Last name",
        "email" to "Email", "phone" to "Phone (10 digits)", "college" to "College / university",
        "degree" to "Degree (e.g. B.Tech)", "branch" to "Branch / major", "gradYear" to "Graduation year",
        "gender" to "Gender (optional)", "city" to "City", "github" to "GitHub URL", "linkedin" to "LinkedIn URL",
        "portfolio" to "Portfolio URL", "resume" to "Resume link"
    )

    fun load(context: Context): Map<String, String> = runCatching {
        val enc = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(DatabaseEncryption.decrypt(enc))
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    fun save(context: Context, values: Map<String, String>) {
        val json = JSONObject().apply { values.filterValues { it.isNotBlank() }.forEach { (k, v) -> put(k, v.trim()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, DatabaseEncryption.encrypt(json.toString())).apply()
    }

    /**
     * JavaScript that fills empty form fields by matching their label / name / placeholder text to profile
     * keys, then fires input+change events so React/Angular forms notice. Never clicks submit.
     */
    fun autofillScript(profile: Map<String, String>): String {
        val p = JSONObject(profile).toString()
        return """
(function(p){
  var rules = [
    ['email', /e-?mail/], ['phone', /phone|mobile|contact (no|number)|whatsapp/],
    ['firstName', /first ?name|given name/], ['lastName', /last ?name|surname|family name/],
    ['fullName', /full ?name|^name$|your name|participant name|candidate name/],
    ['college', /college|university|institute|organi[sz]ation|school/], ['degree', /degree|course|program/],
    ['branch', /branch|major|stream|speciali[sz]ation|department/], ['gradYear', /graduat|passing|batch|year of (study|completion)/],
    ['gender', /gender/], ['city', /city|location/], ['github', /github/], ['linkedin', /linkedin/],
    ['portfolio', /portfolio|website/], ['resume', /resume|cv/]
  ];
  function labelOf(el){
    var t = [el.name, el.id, el.placeholder, el.getAttribute('aria-label')];
    if (el.id) { var l = document.querySelector('label[for="'+el.id+'"]'); if (l) t.push(l.innerText); }
    var c = el.closest('label, .form-group, .field, div'); if (c) t.push((c.innerText||'').slice(0,80));
    return t.filter(Boolean).join(' ').toLowerCase();
  }
  function setVal(el, v){
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set; setter.call(el, v);
    el.dispatchEvent(new Event('input', {bubbles:true})); el.dispatchEvent(new Event('change', {bubbles:true}));
    el.style.outline = '2px solid #7C3AED';
  }
  var filled = 0;
  document.querySelectorAll('input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=file]):not([type=submit]), textarea').forEach(function(el){
    if (el.value) return;
    var l = labelOf(el);
    if (el.type === 'email' && p.email) { setVal(el, p.email); filled++; return; }
    if (el.type === 'tel' && p.phone) { setVal(el, p.phone); filled++; return; }
    for (var i = 0; i < rules.length; i++) {
      var k = rules[i][0];
      if (p[k] && rules[i][1].test(l)) { setVal(el, p[k]); filled++; return; }
    }
  });
  return filled;
})($p)
""".trimIndent()
    }

    /** Text that sites show after a successful registration. */
    val SUCCESS = Regex("successfully registered|registration (is )?(successful|complete)|you('| a)re registered|application (has been )?submitted|thank you for registering", RegexOption.IGNORE_CASE)
}
