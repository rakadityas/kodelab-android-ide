package dev.kodelab.ide.ext

import org.json.JSONObject

/**
 * Kodelab's declarative extension model (Phase A). Extensions are *data only* —
 * no arbitrary code runs — so they are safe by construction: a manifest
 * contributes themes, snippets, grammar/language registrations, and language-
 * server "recipes" (how to install/launch a server the user already trusts).
 *
 * IP: there is no Microsoft Marketplace integration. Everything is gated by a
 * per-extension (and per-contributed-file) SPDX audit — see [ExtensionAudit].
 */
data class SnippetDef(
    val name: String,
    val prefix: String,
    val body: String,
    val languages: List<String> = emptyList(),
    val description: String? = null,
)

data class GrammarDef(
    val languageId: String,
    val extensions: List<String> = emptyList(),
    val file: String? = null,
    val license: String? = null,
)

data class LspRecipe(
    val languageId: String,
    /** e.g. ["apk add gopls"] — run by the user/LSP phase, never here. */
    val install: List<String> = emptyList(),
    /** e.g. ["gopls", "serve"]. */
    val command: List<String> = emptyList(),
    val license: String? = null,
)

data class ThemeContribution(
    val label: String,
    val file: String,
    val license: String? = null,
)

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String?,
    val license: String?,
    val description: String?,
    val themes: List<ThemeContribution> = emptyList(),
    val snippets: List<SnippetDef> = emptyList(),
    val grammars: List<GrammarDef> = emptyList(),
    val lspRecipes: List<LspRecipe> = emptyList(),
) {
    val contributionCount: Int
        get() = themes.size + snippets.size + grammars.size + lspRecipes.size

    companion object {
        /** @throws IllegalArgumentException if required fields are missing/invalid. */
        fun parse(json: String): ExtensionManifest {
            val root = runCatching { JSONObject(json) }
                .getOrElse { throw IllegalArgumentException("Not valid JSON") }
            val id = root.optString("id").ifBlank {
                throw IllegalArgumentException("Extension manifest needs an \"id\"")
            }
            val name = root.optString("name").ifBlank { id }
            val version = root.optString("version").ifBlank { "0.0.0" }
            val publisher = root.optString("publisher").ifBlank { null }
            val license = root.optString("license").ifBlank { null }
            val description = root.optString("description").ifBlank { null }

            val c = root.optJSONObject("contributes") ?: JSONObject()

            val themes = c.optJSONArray("themes").objects().mapNotNull { o ->
                val file = o.optString("file").ifBlank { return@mapNotNull null }
                ThemeContribution(
                    label = o.optString("label").ifBlank { file },
                    file = file,
                    license = o.optString("license").ifBlank { null },
                )
            }
            val snippets = c.optJSONArray("snippets").objects().mapNotNull { o ->
                val prefix = o.optString("prefix").ifBlank { return@mapNotNull null }
                val body = o.optBodyString().ifBlank { return@mapNotNull null }
                SnippetDef(
                    name = o.optString("name").ifBlank { prefix },
                    prefix = prefix,
                    body = body,
                    languages = o.optJSONArray("languages").strings(),
                    description = o.optString("description").ifBlank { null },
                )
            }
            val grammars = c.optJSONArray("grammars").objects().mapNotNull { o ->
                val lang = o.optString("languageId").ifBlank { return@mapNotNull null }
                GrammarDef(
                    languageId = lang,
                    extensions = o.optJSONArray("extensions").strings(),
                    file = o.optString("file").ifBlank { null },
                    license = o.optString("license").ifBlank { null },
                )
            }
            val lsp = (c.optJSONArray("languageServers") ?: c.optJSONArray("lspRecipes"))
                .objects().mapNotNull { o ->
                    val lang = o.optString("languageId").ifBlank { return@mapNotNull null }
                    LspRecipe(
                        languageId = lang,
                        install = o.optJSONArray("install").strings(),
                        command = o.optJSONArray("command").strings(),
                        license = o.optString("license").ifBlank { null },
                    )
                }

            return ExtensionManifest(id, name, version, publisher, license, description, themes, snippets, grammars, lsp)
        }

        // --- small org.json helpers ---
        private fun org.json.JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun org.json.JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

        /** A snippet body may be a string or an array of lines. */
        private fun JSONObject.optBodyString(): String {
            optJSONArray("body")?.let { arr ->
                return (0 until arr.length()).joinToString("\n") { arr.optString(it) }
            }
            return optString("body")
        }
    }
}
