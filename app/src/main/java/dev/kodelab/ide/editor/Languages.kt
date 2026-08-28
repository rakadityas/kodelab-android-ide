package dev.kodelab.ide.editor

/**
 * File-extension → Monaco language id. Monaco ships tokenizers for all of these
 * out of the box (monaco-editor `min/vs`, MIT), so REQ 3 "multiple languages"
 * works offline with no per-language downloads.
 */
object Languages {

    private val byExtension = mapOf(
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript",
        "jsx" to "javascript",
        "ts" to "typescript", "tsx" to "typescript",
        "json" to "json", "jsonc" to "json",
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "rb" to "ruby",
        "go" to "go",
        "rs" to "rust",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp",
        "cs" to "csharp",
        "swift" to "swift",
        "m" to "objective-c",
        "php" to "php",
        "lua" to "lua",
        "dart" to "dart",
        "scala" to "scala",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell",
        "ps1" to "powershell",
        "html" to "html", "htm" to "html",
        "css" to "css", "scss" to "scss", "less" to "less",
        "xml" to "xml", "svg" to "xml",
        "yaml" to "yaml", "yml" to "yaml",
        "toml" to "ini", "ini" to "ini", "properties" to "ini",
        "md" to "markdown", "markdown" to "markdown",
        "sql" to "sql",
        "graphql" to "graphql", "gql" to "graphql",
        "dockerfile" to "dockerfile",
        "gradle" to "groovy", "groovy" to "groovy",
        "r" to "r",
        "pl" to "perl", "pm" to "perl",
        "hcl" to "hcl", "tf" to "hcl",
        "proto" to "protobuf",
    )

    private val byFileName = mapOf(
        "dockerfile" to "dockerfile",
        "makefile" to "makefile",
        "cmakelists.txt" to "cmake",
        ".gitignore" to "plaintext",
    )

    fun forFileName(name: String): String {
        byFileName[name.lowercase()]?.let { return it }
        val ext = name.substringAfterLast('.', "").lowercase()
        return byExtension[ext] ?: "plaintext"
    }
}
