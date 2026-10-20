package app.pawse.scoring

import java.util.Locale

/**
 * The app's words in English or Korean, picked by the phone's language at the
 * moment of the call.
 *
 * Both languages sit side by side at every call site, so a Korean sentence gets
 * its own word order and particles instead of English fragments glued around a
 * number. Android recreates the screen when the language changes, so a call made
 * while drawing is always current.
 *
 * ponytail: two languages inline. A third language is the point to move every
 * string into Android resources.
 */
fun tr(en: String, ko: String): String = if (Locale.getDefault().language == "ko") ko else en
