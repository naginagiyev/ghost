package app.ghost.ui

object Latex {
    private val textCommands = setOf(
        "text", "textrm", "textbf", "textit", "textsf", "texttt", "mathrm", "mathbf", "mathit", "mathsf",
        "mathtt", "mathcal", "mathbb", "boldsymbol", "operatorname", "mbox", "emph", "underline", "overline",
        "hat", "bar", "vec", "tilde", "dot", "displaystyle", "textstyle",
    )
    private val fractions = setOf("frac", "dfrac", "tfrac", "cfrac")
    private val symbols = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "θ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
        "sim" to "~", "simeq" to "≃", "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "sum" to "Σ", "prod" to "Π", "int" to "∫",
        "oint" to "∮", "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "Leftarrow" to "⇐", "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔",
        "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓", "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧",
        "lor" to "∨", "wedge" to "∧", "vee" to "∨", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "circ" to "∘", "degree" to "°", "prime" to "′", "ldots" to "…", "cdots" to "⋯", "dots" to "…",
        "therefore" to "∴", "because" to "∵", "hbar" to "ħ", "ell" to "ℓ", "%" to "%",
        "quad" to "  ", "qquad" to "    ", "lbrace" to "{", "rbrace" to "}", "langle" to "⟨", "rangle" to "⟩",
        "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "mid" to "|", "vert" to "|",
    )
    private val superscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
        '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ', 'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ',
        'e' to 'ᵉ', 'k' to 'ᵏ', 'm' to 'ᵐ', 't' to 'ᵗ', 'T' to 'ᵀ',
    )
    private val subscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇',
        '8' to '₈', '9' to '₉', '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ',
        'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'n' to 'ₙ', 'm' to 'ₘ', 'k' to 'ₖ',
        't' to 'ₜ', 'p' to 'ₚ', 's' to 'ₛ', 'r' to 'ᵣ',
    )
    private val vulgar = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾", "1/5" to "⅕", "1/6" to "⅙",
        "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
    )
    private val spaces = Regex(" {2,}")

    fun convert(source: String): String = Parser(source).all().replace(spaces, " ").trim()

    fun isCommand(name: String): Boolean =
        name in textCommands || name in fractions || name in symbols || name == "sqrt"

    fun commandLength(text: String, start: Int): Int {
        val parser = Parser(text)
        parser.index = start
        parser.token()
        return parser.index - start
    }

    private class Parser(val text: String) {
        var index = 0

        fun all(): String = buildString { while (index < text.length) append(token()) }

        fun token(): String {
            val c = text[index++]
            return when (c) {
                '\\' -> command()
                '{' -> until('}')
                '}' -> ""
                '^' -> superscript(argument())
                '_' -> subscript(argument())
                '&', '~' -> " "
                else -> c.toString()
            }
        }

        private fun until(end: Char): String = buildString {
            while (index < text.length && text[index] != end) append(token())
            if (index < text.length) index++
        }

        private fun argument(): String {
            while (index < text.length && text[index] == ' ') index++
            if (index >= text.length) return ""
            return token()
        }

        private fun command(): String {
            if (index >= text.length) return ""
            val start = index
            if (!text[index].isLetter()) {
                index++
                return when (text[start]) {
                    '\\' -> "; "
                    ',', ':', ';', ' ' -> " "
                    '!' -> ""
                    else -> text[start].toString()
                }
            }
            while (index < text.length && text[index].isLetter()) index++
            val name = text.substring(start, index)
            return when {
                name in textCommands -> argument()
                name in fractions -> fraction(argument(), argument())
                name == "sqrt" -> root()
                name == "left" || name == "right" -> delimiter()
                name == "begin" || name == "end" -> {
                    argument()
                    if (name == "end") " " else ""
                }
                else -> {
                    val symbol = symbols[name] ?: return name
                    if (symbol.length == 1 && symbol[0].isLetter()) {
                        while (index < text.length && text[index] == ' ') index++
                    }
                    symbol
                }
            }
        }

        private fun root(): String {
            var degree = ""
            if (index < text.length && text[index] == '[') {
                index++
                degree = until(']')
            }
            val body = argument()
            val prefix = if (degree.isEmpty()) "" else superscript(degree)
            return prefix + "√" + wrap(body)
        }

        private fun delimiter(): String {
            if (index >= text.length) return ""
            val c = text[index++]
            return when (c) {
                '.' -> ""
                '\\' -> command()
                else -> c.toString()
            }
        }

        private fun fraction(top: String, bottom: String): String =
            vulgar["${top.trim()}/${bottom.trim()}"] ?: (wrap(top) + "/" + wrap(bottom))

        private fun wrap(part: String): String {
            val trimmed = part.trim()
            val simple = trimmed.all { it.isLetterOrDigit() || it == '.' || it in "√′²³" }
            return if (simple) trimmed else "($trimmed)"
        }

        private fun superscript(part: String): String {
            val trimmed = part.trim()
            if (trimmed == "∘" || trimmed == "°") return "°"
            if (trimmed == "′") return "′"
            if (trimmed.isNotEmpty() && trimmed.all { it in superscripts }) return trimmed.map { superscripts.getValue(it) }.joinToString("")
            return if (trimmed.length == 1) "^$trimmed" else "^($trimmed)"
        }

        private fun subscript(part: String): String {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty() && trimmed.all { it in subscripts }) return trimmed.map { subscripts.getValue(it) }.joinToString("")
            return if (trimmed.length == 1) "_$trimmed" else "_($trimmed)"
        }
    }
}
