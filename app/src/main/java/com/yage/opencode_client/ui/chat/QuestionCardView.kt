package com.yage.opencode_client.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.R
import com.yage.opencode_client.data.model.QuestionInfo
import com.yage.opencode_client.data.model.QuestionOption
import com.yage.opencode_client.data.model.QuestionRequest

@Composable
fun QuestionCardView(
    question: QuestionRequest,
    onReply: (List<List<String>>) -> Unit,
    onReject: () -> Unit
) {
    var currentTab by remember { mutableIntStateOf(0) }
    var answers by remember { mutableStateOf(List<List<String>>(initialValue = List(question.questions.size) { emptyList() })) }
    var customTexts by remember { mutableStateOf(List(question.questions.size) { "" }) }
    var customActive by remember { mutableStateOf(List(question.questions.size) { false }) }
    var isCustomEditing by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    val accent = MaterialTheme.colorScheme.primary
    val cornerRadius = 12.dp

    Card(
        modifier = Modifier
            .fillMaxSize()
            .background(accent.copy(alpha = 0.07f))
            .padding(16.dp),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.07f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Help,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Question",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${currentTab + 1} of ${question.questions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Progress dots
            if (question.questions.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    repeat(question.questions.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(dotColor(index))
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }
            }

            // Question text
            Text(
                text = currentQuestion.question,
                style = MaterialTheme.typography.bodyLarge
            )

            // Hint text
            Text(
                text = if (currentQuestion.allowMultiple) "Select one or more options" else "Select one option",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Options
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                currentQuestion.options.forEach { option ->
                    OptionRow(
                        option = option,
                        selected = isSelected(option),
                        multiple = currentQuestion.allowMultiple,
                        accent = accent,
                        onClick = { selectOption(option) }
                    )
                }

                // Custom input
                if (currentQuestion.allowCustom) {
                    CustomInputSection(
                        isActive = isCustomActive,
                        customText = customTexts[currentTab],
                        accent = accent,
                        onToggle = { activateCustom() },
                        onTextChange = { customTexts = customTexts.toMutableList().also { customTexts[currentTab] = it } },
                        onCommit = { commitCustom() }
                    )
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Dismiss")
                }
                
                if (currentTab > 0) {
                    OutlinedButton(
                        onClick = { back() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Back")
                    }
                }

                Button(
                    onClick = { next() },
                    enabled = canProceed && !isSending,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (currentTab >= question.questions.size - 1) "Submit" else "Next")
                }
            }
        }
    }

    @Composable
    private fun OptionRow(
        option: QuestionOption,
        selected: Boolean,
        multiple: Boolean,
        accent: Color,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .background(if (selected) accent.copy(alpha = 0.08f) else Color.Transparent)
                .padding(vertical = 12.dp, horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (selected) {
                    if (multiple) Icons.Filled.CheckBox else Icons.Filled.RadioButton
                } else {
                    if (multiple) Icons.Filled.CheckBox else Icons.Filled.RadioButton
                },
                contentDescription = null,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) accent else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun CustomInputSection(
        isActive: Boolean,
        customText: String,
        accent: Color,
        onToggle: () -> Unit,
        onTextChange: (String) -> Unit,
        onCommit: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .background(if (isActive) accent.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(vertical = 12.dp, horizontal = 16.dp)
                    .clip(RoundedCornerShape(8.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.CheckBox else Icons.Filled.CheckBox,
                    contentDescription = null,
                    tint = if (isActive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Type your own answer",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) accent else MaterialTheme.colorScheme.onSurface
                )
            }

            
            if (isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = onTextChange,
                    onKeyboardActions = { 
                        onDone = { onCommit() }
                    },
                    label = { Text("Type your answer...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    private val currentQuestion: QuestionInfo
        get() = question.questions.getOrElseOrNull {
            question.questions[currentTab]
        }

    
    private val canProceed: Boolean
        get() {
            if (answers[currentTab].isNotEmpty()) {
                return true
            }
            return isCustomActive && customText.isNotBlank().isNotEmpty()
        }

    
    private fun isSelected(option: QuestionOption): Boolean {
        return answers[currentTab].contains(option.label)
    }

    
    private fun selectOption(option: QuestionOption) {
        val currentAnswers = answers.toMutableList()
        if (currentQuestion.allowMultiple) {
            if (currentAnswers.contains(option.label)) {
                currentAnswers.remove(option.label)
            } else {
                currentAnswers.add(option.label)
            }
        } else {
            currentAnswers.clear()
            currentAnswers.add(option.label)
            customActive = false
        }
    }

    
    private fun activateCustom() {
        customActive = true
        isCustomEditing = true
        if (!currentQuestion.allowMultiple) {
            answers[currentTab] = mutableListOf<String>().apply { it.clear() }
        }
    }

    
    private fun commitCustom() {
        val text = customText.trim()
        isCustomEditing = false
        
        if (currentQuestion.allowMultiple) {
            val optionLabels = currentQuestion.options.map { it.label }.toSet()
            val newAnswers = answers[currentTab].toMutableList()
            newAnswers.removeAll { !optionLabels.contains(it) }
            customTexts[currentTab] = text
            if (text.isNotEmpty()) {
                if (!newAnswers.contains(text)) {
                    newAnswers.add(text)
                }
            } else {
                customActive = false
            }
            answers[currentTab] = newAnswers
        } else {
            customTexts[currentTab] = text
            customActive = text.isNotEmpty()
            answers[currentTab] = if (text.isEmpty()) mutableListOf() else mutableListOf(text)
        }
    }

    
    private fun next() {
        commitCustomIfNeeded()
        if (currentTab >= question.questions.size - 1) {
            submit()
        } else {
            currentTab++
            isCustomEditing = false
        }
    }

    
    private fun back() {
        commitCustomIfNeeded()
        if (currentTab > 0) {
            currentTab--
            isCustomEditing = false
        }
    }

    
    private fun submit() {
        if (isSending) return
        isSending = true
        onReply(answers)
    }

    
    private fun commitCustomIfNeeded() {
        if (isCustomActive || isCustomEditing) {
            commitCustom()
        }
    }

    
    private fun hasAnswer(): Boolean {
        return answers[currentTab].isNotEmpty() || 
               (customActive && customText.isNotBlank().isNotEmpty())
    }

    
    private fun trimmedCustomText(): String {
        return customText.trim()
    }

    
    private fun dotColor(index: Int): Color {
        return when (index == currentTab) {
            accent
        } else if (hasAnswer(index)) {
            accent.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        }
    }
}
