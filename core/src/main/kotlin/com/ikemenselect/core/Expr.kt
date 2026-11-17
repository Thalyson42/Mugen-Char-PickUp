package com.ikemenselect.core

/**
 * Avaliador de expressões de gatilho do CNS (subconjunto).
 * Valores: Double, String (símbolos como "A", "S"; textos entre aspas) ou null = desconhecido.
 * A lógica é de três valores: `falso && desconhecido` = falso, `verdadeiro || desconhecido` = verdadeiro.
 */
interface ExprContext {
    /** Valor de um identificador ou função (nome em minúsculas). null = não sei. */
    fun ident(name: String, args: List<Any?>?): Any?
}

object Expr {

    sealed interface Node
    private class Num(val v: Double) : Node
    private class Str(val s: String) : Node
    private class Id(val name: String, val args: List<Node>?) : Node
    private class Un(val op: String, val a: Node) : Node
    private class Bin(val op: String, val l: Node, val r: Node) : Node
    private class Interval(val negate: Boolean, val l: Node, val lo: Node, val hi: Node, val loOpen: Boolean, val hiOpen: Boolean) : Node

    private val cache = HashMap<String, Node?>()

    /** Lê a expressão. Devolve null se usa algo que não suportamos (ex.: redirecionamento com vírgula). */
    private fun parse(text: String): Node? = synchronized(cache) {
        cache.getOrPut(text) { Parser(tokenize(text) ?: return@getOrPut null).parseAll() }
    }

    /** Avalia. null = desconhecido. */
    fun eval(text: String, ctx: ExprContext): Any? {
        val n = parse(text) ?: return null
        return try {
            ev(n, ctx)
        } catch (e: Exception) {
            null
        }
    }

    /** true/false/null (desconhecido). Número diferente de zero = verdadeiro. */
    fun truth(text: String, ctx: ExprContext): Boolean? = asBool(eval(text, ctx))

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Double -> v != 0.0
        else -> null
    }

    // ------------------------------------------------------------------ avaliação

    private fun ev(n: Node, c: ExprContext): Any? = when (n) {
        is Num -> n.v
        is Str -> n.s
        is Id -> {
            val args = n.args?.map { ev(it, c) }
            c.ident(n.name, args)
        }
        is Un -> {
            val a = ev(n.a, c)
            when (n.op) {
                "!" -> asBool(a)?.let { if (it) 0.0 else 1.0 }
                "-" -> (a as? Double)?.let { -it }
                "~" -> (a as? Double)?.let { it.toLong().inv().toDouble() }
                else -> null
            }
        }
        is Interval -> {
            val v = ev(n.l, c) as? Double
            val lo = ev(n.lo, c) as? Double
            val hi = ev(n.hi, c) as? Double
            if (v == null || lo == null || hi == null) null else {
                val inside = (if (n.loOpen) v > lo else v >= lo) && (if (n.hiOpen) v < hi else v <= hi)
                if (inside != n.negate) 1.0 else 0.0
            }
        }
        is Bin -> evBin(n, c)
    }

    private fun b(v: Boolean) = if (v) 1.0 else 0.0

    private fun evBin(n: Bin, c: ExprContext): Any? {
        when (n.op) {
            "&&" -> {
                val l = asBool(ev(n.l, c))
                if (l == false) return 0.0
                val r = asBool(ev(n.r, c))
                if (r == false) return 0.0
                return if (l == true && r == true) 1.0 else null
            }
            "||" -> {
                val l = asBool(ev(n.l, c))
                if (l == true) return 1.0
                val r = asBool(ev(n.r, c))
                if (r == true) return 1.0
                return if (l == false && r == false) 0.0 else null
            }
            "^^" -> {
                val l = asBool(ev(n.l, c)) ?: return null
                val r = asBool(ev(n.r, c)) ?: return null
                return b(l != r)
            }
        }
        val l = ev(n.l, c)
        val r = ev(n.r, c)
        if (l == null || r == null) return null
        if (n.op == "=" || n.op == "!=") {
            val eq = when {
                l is Double && r is Double -> l == r
                l is String && r is String -> l.equals(r, ignoreCase = true)
                else -> return null
            }
            return b(if (n.op == "=") eq else !eq)
        }
        val x = l as? Double ?: return null
        val y = r as? Double ?: return null
        return when (n.op) {
            "<" -> b(x < y)
            ">" -> b(x > y)
            "<=" -> b(x <= y)
            ">=" -> b(x >= y)
            "+" -> x + y
            "-" -> x - y
            "*" -> x * y
            "/" -> if (y == 0.0) null else x / y
            "%" -> if (y.toLong() == 0L) null else (x.toLong() % y.toLong()).toDouble()
            "**" -> Math.pow(x, y)
            "|" -> (x.toLong() or y.toLong()).toDouble()
            "&" -> (x.toLong() and y.toLong()).toDouble()
            "^" -> (x.toLong() xor y.toLong()).toDouble()
            else -> null
        }
    }

    // ------------------------------------------------------------------ análise

    private class Tok(val kind: Char, val text: String) // n = número, i = identificador, s = texto, o = operador

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || (ch == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out.add(Tok('n', s.substring(i, j)))
                    i = j
                }
                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
                    out.add(Tok('i', s.substring(i, j).lowercase()))
                    i = j
                }
                ch == '"' -> {
                    val j = s.indexOf('"', i + 1)
                    if (j < 0) return null
                    out.add(Tok('s', s.substring(i + 1, j)))
                    i = j + 1
                }
                else -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    if (two in setOf("&&", "||", "^^", "!=", "<=", ">=", "**", ":=")) {
                        out.add(Tok('o', two)); i += 2
                    } else if (ch in "+-*/%<>=!&|^~(),[]") {
                        out.add(Tok('o', ch.toString())); i++
                    } else return null
                }
            }
        }
        return out
    }

    private class Parser(val t: List<Tok>) {
        var p = 0
        fun peek(): Tok? = t.getOrNull(p)
        fun isOp(s: String) = peek()?.let { it.kind == 'o' && it.text == s } == true
        fun eat(s: String): Boolean { if (isOp(s)) { p++; return true }; return false }

        fun parseAll(): Node? {
            val n = or() ?: return null
            return if (p == t.size) n else null // sobrou algo (vírgula, redirecionamento...): não suportado
        }

        fun or(): Node? {
            var l = xor() ?: return null
            while (eat("||")) l = Bin("||", l, xor() ?: return null)
            return l
        }
        fun xor(): Node? {
            var l = and() ?: return null
            while (eat("^^")) l = Bin("^^", l, and() ?: return null)
            return l
        }
        fun and(): Node? {
            var l = bor() ?: return null
            while (eat("&&")) l = Bin("&&", l, bor() ?: return null)
            return l
        }
        fun bor(): Node? {
            var l = bxor() ?: return null
            while (isOp("|")) { p++; l = Bin("|", l, bxor() ?: return null) }
            return l
        }
        fun bxor(): Node? {
            var l = band() ?: return null
            while (isOp("^")) { p++; l = Bin("^", l, band() ?: return null) }
            return l
        }
        fun band(): Node? {
            var l = cmp() ?: return null
            while (isOp("&")) { p++; l = Bin("&", l, cmp() ?: return null) }
            return l
        }
        fun cmp(): Node? {
            val l = add() ?: return null
            for (op in listOf("=", "!=", "<=", ">=", "<", ">")) {
                if (isOp(op)) {
                    p++
                    if (op == "=" || op == "!=") interval(l, op == "!=")?.let { return it }
                    return Bin(op, l, add() ?: return null)
                }
            }
            return l
        }
        /** x = [a,b] / x != (a,b) ... Se não for intervalo, volta e deixa o chamador tratar. */
        fun interval(l: Node, negate: Boolean): Node? {
            val save = p
            val open = when {
                isOp("[") -> false
                isOp("(") -> true
                else -> return null
            }
            p++
            val lo = add()
            if (lo == null || !eat(",")) { p = save; return null }
            val hi = add()
            if (hi == null) { p = save; return null }
            val hiOpen = when {
                isOp("]") -> false
                isOp(")") -> true
                else -> { p = save; return null }
            }
            p++
            return Interval(negate, l, lo, hi, open, hiOpen)
        }
        fun add(): Node? {
            var l = mul() ?: return null
            while (isOp("+") || isOp("-")) { val op = t[p++].text; l = Bin(op, l, mul() ?: return null) }
            return l
        }
        fun mul(): Node? {
            var l = pow() ?: return null
            while (isOp("*") || isOp("/") || isOp("%")) { val op = t[p++].text; l = Bin(op, l, pow() ?: return null) }
            return l
        }
        fun pow(): Node? {
            val l = unary() ?: return null
            if (eat("**")) return Bin("**", l, pow() ?: return null)
            return l
        }
        fun unary(): Node? {
            if (isOp("!") || isOp("-") || isOp("~")) {
                val op = t[p++].text
                return Un(op, unary() ?: return null)
            }
            return primary()
        }
        fun primary(): Node? {
            val k = peek() ?: return null
            when (k.kind) {
                'n' -> { p++; return Num(k.text.toDoubleOrNull() ?: return null) }
                's' -> { p++; return Str(k.text) }
                'i' -> {
                    p++
                    if (isOp("(")) {
                        p++
                        val args = ArrayList<Node>()
                        if (!isOp(")")) {
                            while (true) {
                                args.add(or() ?: return null)
                                if (eat(",")) continue
                                break
                            }
                        }
                        if (!eat(")")) return null
                        return Id(k.text, args)
                    }
                    return Id(k.text, null)
                }
                'o' -> {
                    if (k.text == "(") {
                        p++
                        val n = or() ?: return null
                        if (!eat(")")) return null
                        return n
                    }
                    return null
                }
            }
            return null
        }
    }
}
