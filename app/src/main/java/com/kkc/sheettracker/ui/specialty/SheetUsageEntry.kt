package com.kkc.sheettracker.ui.specialty

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kkc.sheettracker.data.parseWholeSheets

/** No default/estimated count: an empty record stays empty until explicitly saved. */
@Composable
internal fun SheetUsageEntry(material: String, count: Int?, readOnly: Boolean, onSave: (Int) -> Unit,
    modifier: Modifier = Modifier, stateKey: String = material) {
    if (readOnly) {
        Text("Sheets used: ${count?.toString() ?: "Not entered"}", style = MaterialTheme.typography.bodySmall,
            modifier = modifier)
        return
    }
    var draft by rememberSaveable(stateKey, count) { mutableStateOf(count?.toString().orEmpty()) }
    val parsed = parseWholeSheets(draft)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = draft, onValueChange = { next ->
            if (next.isEmpty() || parseWholeSheets(next) != null) draft = next
        }, label = { Text("Sheets used (optional)") }, placeholder = { Text("Not entered") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null && parsed != count,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
    }
}
