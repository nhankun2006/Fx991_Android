package io.paimon.fx991.engine

// ---------------------------------------------------------------------------
// Batch K3: Syntax tree -> linear text (fully parenthesized, guaranteed to be readable back by Lexer/Parser)
//
// Purpose: The first parameter of int(expr, lower, upper) / deriv(expr, x) / sum(expr, a, b) / lim(expr, x0)
// is an "expression of x". When passed into NumericOps it requires text form — algorithm is unchanged, AST transcription only.
// ---------------------------------------------------------------------------

internal object NodeText {

    fun render(n: Node): String = when (n) {
        is Node.Num -> n.lit ?: CalcEngine.literal(n.v)
        is Node.AnsRef -> "Ans"
        is Node.PreAnsRef -> "PreAns"
        is Node.MemRef -> "M"
        is Node.XRef -> "x"
        is Node.YRef -> "y"
        is Node.ZRef -> "z"
        is Node.IRef -> "i"
        is Node.VarRef -> n.name
        is Node.MatRef -> "Mat${n.name}"
        is Node.VecRef -> "Vct${n.name}"
        is Node.Neg -> "-(" + render(n.a) + ")"
        is Node.Add -> "(" + render(n.a) + ")+(" + render(n.b) + ")"
        is Node.Sub -> "(" + render(n.a) + ")-(" + render(n.b) + ")"
        is Node.Mul -> "(" + render(n.a) + ")*(" + render(n.b) + ")"
        is Node.Div -> "(" + render(n.a) + ")/(" + render(n.b) + ")"
        is Node.Pow -> "(" + render(n.a) + ")^(" + render(n.b) + ")"
        is Node.Fact -> "(" + render(n.a) + ")!"
        is Node.Pct -> "(" + render(n.a) + ")%"
        is Node.Recip -> "(" + render(n.a) + ")\u207B\u00B9"
        is Node.Sqrt -> "\u221A(" + render(n.a) + ")"
        is Node.Fn -> n.name + "(" + render(n.a) + ")"
        is Node.Fn2 -> n.name + "(" + render(n.a) + "," + render(n.b) + ")"
        is Node.FnN -> n.args.joinToString(",", prefix = n.name + "(", postfix = ")") { render(it) }
        is Node.Polar -> "(" + render(n.r) + ")\u2220(" + render(n.t) + ")"
        is Node.Dot -> "(" + render(n.a) + ")\u00B7(" + render(n.b) + ")"
        is Node.UFn -> n.args.joinToString(
            ",",
            prefix = n.name + "'".repeat(n.deriv) + "(",
            postfix = ")",
        ) { render(it) }
    }
}
