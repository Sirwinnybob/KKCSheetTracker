package com.kkc.sheettracker.ui.supply

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.models.SupplySchemaField

/**
 * One schema-driven field editor. Chooses the keyboard by field type; date renders as a
 * plain text input with a YYYY-MM-DD hint (a Material date picker is a follow-up). Values
 * are always plain strings.
 */
@Composable
fun SupplyFieldInput(
    field: SupplySchemaField,
    value: String,
    onValueChange: (String) -> Unit
) {
    val keyboardType = when (field.type) {
        "number" -> KeyboardType.Number
        "url" -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
    val label = if (field.type == "date") "${field.label} (YYYY-MM-DD)" else field.label
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(4.dp)
    )
}
