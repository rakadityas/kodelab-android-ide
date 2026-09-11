package dev.kodelab.ide.ext

/**
 * Per-extension and per-contributed-file SPDX audit (REQ 4). An extension is
 * only auto-activated when everything it ships is clearly permissive; anything
 * copyleft, unknown, or undeclared is *flagged* with a reason and left inactive
 * until the user opts in. Pure and unit-testable.
 */
object ExtensionAudit {

    enum class Verdict { ALLOWED, FLAGGED }

    data class Issue(val subject: String, val license: String?, val reason: String)

    data class Result(val verdict: Verdict, val issues: List<Issue>) {
        val allowed: Boolean get() = verdict == Verdict.ALLOWED
    }

    fun audit(manifest: ExtensionManifest): Result {
        val issues = mutableListOf<Issue>()

        licenseIssue("extension", manifest.license)?.let { issues += it }

        // A contributed file with its own license is checked on its own terms;
        // one without inherits the extension license (already checked above).
        for (t in manifest.themes) licenseIssue("theme “${t.label}”", t.license, inheritFrom = manifest.license)?.let { issues += it }
        for (g in manifest.grammars) licenseIssue("grammar ${g.languageId}", g.license, inheritFrom = manifest.license)?.let { issues += it }
        for (r in manifest.lspRecipes) licenseIssue("language server ${r.languageId}", r.license, inheritFrom = manifest.license)?.let { issues += it }

        val verdict = if (issues.isEmpty()) Verdict.ALLOWED else Verdict.FLAGGED
        return Result(verdict, issues)
    }

    /**
     * @param inheritFrom when the subject declares no license of its own, it
     * inherits this one — so we don't double-report the extension-level problem.
     */
    private fun licenseIssue(subject: String, license: String?, inheritFrom: String? = null): Issue? {
        val effective = license ?: inheritFrom
        // No own license and nothing to inherit → only report at the top level.
        if (license == null && inheritFrom != null) return null
        if (effective.isNullOrBlank()) {
            return Issue(subject, null, "no license declared")
        }
        if (SpdxLicenses.isPermissive(effective)) return null
        val kinds = SpdxLicenses.leaves(effective).map { SpdxLicenses.classifyLeaf(it) }
        val reason = when {
            kinds.any { it == SpdxLicenses.Leaf.COPYLEFT } ->
                "copyleft license — usable but not auto-activated"
            else -> "unrecognised license — can't confirm it's safe to reuse"
        }
        return Issue(subject, effective, reason)
    }
}
