package com.kkc.sheettracker.ui.components

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class NavBarShopKeypadInputTest {

    @Test
    fun shopKeypadInput_appendsAllowedKeysAndDeletesTheLastCharacter() {
        var value = applyShopKey("12", "A")
        value = applyShopKey(value.text, "7")
        value = applyShopKey(value.text, "⌫")

        assertEquals("12A", value.text)
    }

    @Test
    fun shopKeypadInput_ignoresKeysOutsideTheShopKeypadAlphabet() {
        assertEquals("12A", applyShopKey("12A", "Z").text)
    }

    @Test
    fun shopKeypad_usesTheStandardThreeColumnNumericLayout() {
        val keypadRowsField = Class.forName("com.kkc.sheettracker.ui.components.AppScaffoldKt")
            .getDeclaredField("ShopKeypadRows")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = keypadRowsField.get(null) as List<List<String>>

        assertEquals(
            listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("0", "A", "B"),
                listOf("C", "D", "E"),
                listOf("F", "G", "\u0008")
            ),
            rows
        )
    }

    private fun applyShopKey(text: String, key: String): TextFieldValue {
        return applyShopKeypadKey(TextFieldValue(text), key)
    }
}
