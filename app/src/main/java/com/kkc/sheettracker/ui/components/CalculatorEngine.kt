package com.kkc.sheettracker.ui.components

import kotlin.math.abs

data class CalculatorEngineState(
    val expression: String = "0",
    val display: String = "0",
    val memory: Double = 0.0,
    val hasError: Boolean = false,
    val justEvaluated: Boolean = false
)

object CalculatorEngine {
    fun loadExpression(state: CalculatorEngineState, expression: String): CalculatorEngineState {
        val decorated = expression.trim()
            .replace('x', '×')
            .replace('X', '×')
            .replace('*', '×')
            .replace('/', '÷')
        val normalized = decorated
            .replace('×', '*')
            .replace('÷', '/')
        val tokens = tokenize(normalized)
        if (tokens.isNullOrEmpty()) {
            return state.copy(
                expression = "0",
                display = "0",
                hasError = false,
                justEvaluated = false
            )
        }
        return state.copy(
            expression = decorated,
            display = currentOperand(normalized).ifBlank { "0" },
            hasError = false,
            justEvaluated = false
        )
    }

    fun press(state: CalculatorEngineState, key: String): CalculatorEngineState {
        return when (key) {
            in DIGITS -> appendDigit(state, key)
            "." -> appendDecimal(state)
            "+", "-", "x", "X", "*", "×", "/", "÷" -> appendOperator(state, normalizeOperator(key))
            "C" -> CalculatorEngineState(memory = state.memory)
            "⌫" -> backspace(state)
            "=" -> evaluate(state)
            "MC" -> state.copy(memory = 0.0)
            "MR" -> memoryRecall(state)
            "M+" -> memoryAdjust(state, deltaSign = 1.0)
            "M-" -> memoryAdjust(state, deltaSign = -1.0)
            else -> state
        }
    }

    private fun appendDigit(state: CalculatorEngineState, digit: String): CalculatorEngineState {
        val base = sanitizeForInput(state)
        val nextExpression = when {
            base.justEvaluated -> digit
            base.expression == "0" -> digit
            currentOperand(base.expression) == "0" && !base.expression.endsWith(".") -> {
                val replaceAt = findOperandStartIndex(base.expression)
                base.expression.substring(0, replaceAt) + digit
            }
            else -> base.expression + digit
        }
        return base.copy(
            expression = nextExpression,
            display = currentOperand(nextExpression),
            hasError = false,
            justEvaluated = false
        )
    }

    private fun appendDecimal(state: CalculatorEngineState): CalculatorEngineState {
        val base = sanitizeForInput(state)
        if (base.justEvaluated) {
            return base.copy(
                expression = "0.",
                display = "0.",
                hasError = false,
                justEvaluated = false
            )
        }
        val operand = currentOperand(base.expression)
        if (operand.contains('.')) return base
        val nextExpression = if (
            base.expression.isEmpty() ||
            isOperator(base.expression.last())
        ) {
            base.expression + "0."
        } else {
            base.expression + "."
        }
        return base.copy(
            expression = nextExpression,
            display = currentOperand(nextExpression),
            hasError = false,
            justEvaluated = false
        )
    }

    private fun appendOperator(state: CalculatorEngineState, op: Char): CalculatorEngineState {
        var base = sanitizeForInput(state)
        val fromDisplay = if (base.justEvaluated) {
            formatNumber(parseAsNumber(base.display) ?: 0.0)
        } else {
            base.expression
        }
        var expr = fromDisplay.trim()
        if (expr.isEmpty()) expr = "0"
        expr = expr.replace('x', '*').replace('X', '*').replace('×', '*').replace('÷', '/')
        val nextExpression = if (isOperator(expr.last())) {
            expr.dropLast(1) + op
        } else {
            expr + op
        }
        base = base.copy(expression = nextExpression)
        return base.copy(
            display = currentOperand(nextExpression).ifBlank { formatOperatorForDisplay(op) },
            hasError = false,
            justEvaluated = false
        )
    }

    private fun backspace(state: CalculatorEngineState): CalculatorEngineState {
        val base = sanitizeForInput(state)
        val source = if (base.justEvaluated) base.display else base.expression
        if (source.length <= 1) {
            return base.copy(expression = "0", display = "0", hasError = false, justEvaluated = false)
        }
        val nextExpression = source.dropLast(1).ifBlank { "0" }
        val nextDisplay = when {
            nextExpression == "0" -> "0"
            isOperator(nextExpression.last()) -> formatOperatorForDisplay(nextExpression.last())
            else -> currentOperand(nextExpression)
        }
        return base.copy(
            expression = nextExpression,
            display = nextDisplay,
            hasError = false,
            justEvaluated = false
        )
    }

    private fun evaluate(state: CalculatorEngineState): CalculatorEngineState {
        val expression = state.expression.trim().replace('x', '*').replace('X', '*').replace('×', '*').replace('÷', '/')
        val value = evaluateExpression(expression) ?: return state.copy(
            display = "Error",
            hasError = true,
            justEvaluated = true
        )
        val formatted = formatNumber(value)
        return state.copy(
            expression = formatted,
            display = formatted,
            hasError = false,
            justEvaluated = true
        )
    }

    private fun memoryAdjust(state: CalculatorEngineState, deltaSign: Double): CalculatorEngineState {
        val baseValue = parseAsNumber(state.display)
            ?: evaluateExpression(state.expression.replace('x', '*').replace('X', '*').replace('×', '*').replace('÷', '/'))
            ?: return state
        return state.copy(memory = state.memory + (deltaSign * baseValue))
    }

    private fun memoryRecall(state: CalculatorEngineState): CalculatorEngineState {
        val recalled = formatNumber(state.memory)
        val base = sanitizeForInput(state)
        val expression = when {
            base.justEvaluated -> recalled
            base.expression == "0" -> recalled
            isOperator(base.expression.last()) -> base.expression + recalled
            else -> recalled
        }
        return base.copy(
            expression = expression,
            display = recalled,
            hasError = false,
            justEvaluated = false
        )
    }

    private fun sanitizeForInput(state: CalculatorEngineState): CalculatorEngineState {
        if (!state.hasError) return state
        return state.copy(expression = "0", display = "0", hasError = false, justEvaluated = false)
    }

    private fun normalizeOperator(input: String): Char {
        return when (input) {
            "x", "X", "*", "×" -> '*'
            "÷", "/" -> '/'
            "+" -> '+'
            "-" -> '-'
            else -> '+'
        }
    }

    private fun formatOperatorForDisplay(op: Char): String {
        return when (op) {
            '*' -> "×"
            '/' -> "÷"
            else -> op.toString()
        }
    }

    private fun currentOperand(expression: String): String {
        val lastPlus = expression.lastIndexOf('+')
        val lastMul = expression.lastIndexOf('*')
        val lastDiv = expression.lastIndexOf('/')
        val lastMinus = findBinaryMinus(expression)
        val boundary = maxOf(lastPlus, lastMul, lastDiv, lastMinus)
        return when {
            boundary < 0 -> expression
            boundary + 1 < expression.length -> expression.substring(boundary + 1)
            else -> ""
        }
    }

    private fun findBinaryMinus(expression: String): Int {
        var index = -1
        expression.forEachIndexed { i, c ->
            if (c == '-' && i > 0 && !isOperator(expression[i - 1])) {
                index = i
            }
        }
        return index
    }

    private fun findOperandStartIndex(expression: String): Int {
        val lastPlus = expression.lastIndexOf('+')
        val lastMul = expression.lastIndexOf('*')
        val lastDiv = expression.lastIndexOf('/')
        val lastMinus = findBinaryMinus(expression)
        val boundary = maxOf(lastPlus, lastMul, lastDiv, lastMinus)
        return if (boundary >= 0) boundary + 1 else 0
    }

    private fun parseAsNumber(text: String): Double? {
        val normalized = text.replace(",", "").trim()
        return normalized.toDoubleOrNull()
    }

    private fun evaluateExpression(expression: String): Double? {
        if (expression.isBlank()) return null
        val tokens = tokenize(expression) ?: return null
        if (tokens.isEmpty()) return null
        val values = ArrayDeque<Double>()
        val ops = ArrayDeque<Char>()

        fun applyTopOperator(): Boolean {
            if (ops.isEmpty() || values.size < 2) return false
            val op = ops.removeLast()
            val right = values.removeLast()
            val left = values.removeLast()
            val result = when (op) {
                '+' -> left + right
                '-' -> left - right
                '*' -> left * right
                '/' -> {
                    if (abs(right) < EPSILON) return false
                    left / right
                }
                else -> return false
            }
            if (!result.isFinite()) return false
            values.addLast(result)
            return true
        }

        for (token in tokens) {
            if (token.length == 1 && isOperator(token[0])) {
                val incoming = token[0]
                while (ops.isNotEmpty() && precedence(ops.last()) >= precedence(incoming)) {
                    if (!applyTopOperator()) return null
                }
                ops.addLast(incoming)
            } else {
                values.addLast(token.toDoubleOrNull() ?: return null)
            }
        }
        while (ops.isNotEmpty()) {
            if (!applyTopOperator()) return null
        }
        return if (values.size == 1) values.last() else null
    }

    private fun tokenize(expression: String): List<String>? {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' || (c == '-' && isUnaryMinusPosition(expression, i)) -> {
                    val start = i
                    i++
                    while (i < expression.length && (expression[i].isDigit() || expression[i] == '.')) {
                        i++
                    }
                    val token = expression.substring(start, i)
                    if (token == "-" || token == "." || token == "-.") return null
                    token.toDoubleOrNull() ?: return null
                    tokens += token
                }
                isOperator(c) -> {
                    if (tokens.isEmpty()) return null
                    tokens += c.toString()
                    i++
                }
                else -> return null
            }
        }
        if (tokens.isNotEmpty() && tokens.last().length == 1 && isOperator(tokens.last()[0])) return null
        return tokens
    }

    private fun isUnaryMinusPosition(expression: String, index: Int): Boolean {
        if (expression[index] != '-') return false
        if (index == 0) return true
        val prev = expression[index - 1]
        return isOperator(prev)
    }

    private fun isOperator(char: Char): Boolean = char == '+' || char == '-' || char == '*' || char == '/'

    private fun precedence(op: Char): Int = if (op == '+' || op == '-') 1 else 2

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return "Error"
        val roundedInt = value.toLong()
        if (abs(value - roundedInt.toDouble()) < EPSILON) return roundedInt.toString()
        var text = value.toString()
        if (text.contains('E') || text.contains('e')) {
            text = java.math.BigDecimal(value).stripTrailingZeros().toPlainString()
        } else if (text.contains('.')) {
            text = text.trimEnd('0').trimEnd('.')
        }
        return if (text == "-0") "0" else text
    }

    private val DIGITS = ('0'..'9').map { it.toString() }.toSet()
    private const val EPSILON = 1e-10
}
