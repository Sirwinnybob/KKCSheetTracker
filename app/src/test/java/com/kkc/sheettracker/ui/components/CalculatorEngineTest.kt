package com.kkc.sheettracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEngineTest {

    @Test
    fun evaluates_basicExpression_withPrecedence() {
        val state = runKeys("2", "+", "3", "×", "4", "=")
        assertEquals("14", state.display)
        assertEquals("14", state.expression)
    }

    @Test
    fun supports_decimalMath_and_backspace() {
        val state = runKeys("1", ".", "5", "⌫", "5", "+", "0", ".", "5", "=")
        assertEquals("2", state.display)
    }

    @Test
    fun memory_operations_add_subtract_recall_clear() {
        val plusState = runKeys("8", "M+", "C", "MR")
        assertEquals("8", plusState.display)

        val minusState = runKeysFrom(plusState, "C", "2", "M-", "C", "MR")
        assertEquals("6", minusState.display)

        val cleared = runKeysFrom(minusState, "MC", "C", "MR")
        assertEquals("0", cleared.display)
    }

    @Test
    fun divideByZero_setsErrorState() {
        val state = runKeys("7", "÷", "0", "=")
        assertEquals("Error", state.display)
        assertTrue(state.hasError)
    }

    @Test
    fun malformedExpression_setsErrorState() {
        val state = runKeys("9", "+", "=")
        assertEquals("Error", state.display)
        assertTrue(state.hasError)
    }

    @Test
    fun division_isLeftAssociative() {
        val state = runKeys("8", "÷", "4", "÷", "2", "=")
        assertEquals("1", state.display)
    }

    @Test
    fun subtraction_and_multiplication_precedence_isCorrect() {
        val state = runKeys("1", "0", "-", "2", "×", "3", "=")
        assertEquals("4", state.display)
    }

    @Test
    fun decimal_entry_blocks_doubleDecimal_in_singleOperand() {
        val state = runKeys("1", ".", ".", "2", "=")
        assertEquals("1.2", state.display)
    }

    @Test
    fun decimal_can_start_from_dot_key() {
        val s1 = runKeys(".")
        assertEquals("0.", s1.display)
        val s2 = runKeysFrom(s1, "5")
        assertEquals("0.5", s2.display)
        val s3 = runKeysFrom(s2, "+")
        val s4 = runKeysFrom(s3, ".")
        assertEquals("0.", s4.display)
        val s5 = runKeysFrom(s4, "5")
        assertEquals("0.5", s5.display)
        val state = runKeysFrom(s5, "=")
        assertEquals("1", state.display)
    }

    @Test
    fun operator_replaces_previous_operator_when_tapped_twice() {
        val state = runKeys("5", "+", "-", "2", "=")
        assertEquals("3", state.display)
    }

    @Test
    fun clear_resets_expression_but_keeps_memory() {
        val state = runKeys("9", "M+", "C", "2", "+", "2", "=")
        assertEquals("4", state.display)
        val recall = runKeysFrom(state, "MR")
        assertEquals("9", recall.display)
    }

    @Test
    fun backspace_after_evaluation_edits_result() {
        val state = runKeys("1", "2", "+", "3", "=", "⌫")
        assertEquals("1", state.display)
    }

    @Test
    fun equals_on_singleNumber_isStable() {
        val state = runKeys("4", "2", "=", "=")
        assertEquals("42", state.display)
        assertTrue(state.justEvaluated)
    }

    @Test
    fun loadExpression_restoresEditableExpression() {
        val start = CalculatorEngineState(memory = 12.0)
        val loaded = CalculatorEngine.loadExpression(start, "12.5+3")
        assertEquals("12.5+3", loaded.expression)
        assertEquals("3", loaded.display)
        assertEquals(12.0, loaded.memory, 0.0)
        assertTrue(!loaded.justEvaluated)
    }

    @Test
    fun loadExpression_thenEvaluate_matchesExpectedResult() {
        val loaded = CalculatorEngine.loadExpression(CalculatorEngineState(), "2×3+4")
        val result = CalculatorEngine.press(loaded, "=")
        assertEquals("10", result.display)
    }

    @Test
    fun loadExpression_invalidInput_fallsBackToZero() {
        val loaded = CalculatorEngine.loadExpression(CalculatorEngineState(), "abc")
        assertEquals("0", loaded.expression)
        assertEquals("0", loaded.display)
    }

    @Test
    fun smallResult_inScientificRange_formatsWithoutBinaryGarbage() {
        // 1 / 10_000_000 = 1e-7, whose Double.toString uses 'E' notation and hits the
        // BigDecimal formatting branch. BigDecimal(double) would emit the exact binary
        // expansion ("0.000000100000000000000004792..."); BigDecimal.valueOf(double) is clean.
        val state = runKeys("1", "÷", "1", "0", "0", "0", "0", "0", "0", "0", "=")
        assertEquals("0.0000001", state.display)
    }

    private fun runKeys(vararg keys: String): CalculatorEngineState {
        return runKeysFrom(CalculatorEngineState(), *keys)
    }

    private fun runKeysFrom(initial: CalculatorEngineState, vararg keys: String): CalculatorEngineState {
        return keys.fold(initial) { acc, key -> CalculatorEngine.press(acc, key) }
    }
}
