package com.jamilxt.k8sconfigmap.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ConfigMapTarget
import com.jamilxt.k8sconfigmap.settings.NavigatorSettings
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class AddToConfigMapDialog(
    private val project: Project,
    private val rawKeys: List<String>,
    defaultValue: String?,
    private val configMaps: List<ConfigMapTarget>
) : DialogWrapper(project) {

    private val rawKeyCombo: ComboBox<String>? = if (rawKeys.size > 1) {
        ComboBox(rawKeys.toTypedArray())
    } else {
        null
    }

    private val upperSnakeCheckBox = JBCheckBox("Format as UPPER_SNAKE_CASE", true)
    private val keyField = JBTextField(formatKey(rawKeys.first(), true), 36)
    private val valueField = JBTextField(defaultValue.orEmpty(), 36)
    private val openAfterAddCheckBox = JBCheckBox("Open ConfigMap after adding", true)

    private val targetCheckBoxes: List<Pair<ConfigMapTarget, JBCheckBox>>

    init {
        title = "Add to Kubernetes ConfigMap"
        setOKButtonText("Add")

        val initialKey = keyField.text.trim()
        targetCheckBoxes = configMaps.map { target ->
            val existingMatch = findExistingMatch(target, initialKey)
            val cb = JBCheckBox(buildTargetLabel(target, existingMatch))
            cb.isSelected = existingMatch == null
            target to cb
        }

        // If all ConfigMaps already have the key, leave them unchecked so the user explicitly chooses to update.
        // If only some are missing, those missing ones are pre-checked.
        rawKeyCombo?.addActionListener {
            val selectedRaw = rawKeyCombo.selectedItem as? String ?: return@addActionListener
            keyField.text = formatKey(selectedRaw, upperSnakeCheckBox.isSelected)
        }

        upperSnakeCheckBox.addActionListener {
            val baseRaw = (rawKeyCombo?.selectedItem as? String) ?: rawKeys.first()
            keyField.text = formatKey(baseRaw, upperSnakeCheckBox.isSelected)
        }

        keyField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTargetLabels()
            override fun removeUpdate(e: DocumentEvent) = refreshTargetLabels()
            override fun changedUpdate(e: DocumentEvent) = refreshTargetLabels()
        })

        init()
    }

    val configMapKey: String
        get() = keyField.text.trim()

    val configMapValue: String
        get() = valueField.text

    val selectedTargets: List<ConfigMapTarget>
        get() = targetCheckBoxes.filter { it.second.isSelected }.map { it.first }

    val openAfterAdd: Boolean
        get() = openAfterAddCheckBox.isSelected

    override fun createCenterPanel(): JComponent {
        val listPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for ((_, cb) in targetCheckBoxes) {
                add(cb)
            }
        }
        val scrollPane = JBScrollPane(listPanel).apply {
            preferredSize = Dimension(480, minOf(220, maxOf(72, targetCheckBoxes.size * 28 + 12)))
        }
        val targetsWrapper = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
        }

        return panel {
            if (rawKeyCombo != null) {
                row("Source property:") {
                    cell(rawKeyCombo).align(AlignX.FILL)
                }
            }
            row("ConfigMap key:") {
                cell(keyField).align(AlignX.FILL).focused()
            }
            row("") {
                cell(upperSnakeCheckBox)
            }
            row("Value:") {
                cell(valueField).align(AlignX.FILL)
            }
            row("Target ConfigMap(s):") {}
            row {
                cell(targetsWrapper).align(AlignX.FILL)
            }
            row {
                cell(openAfterAddCheckBox)
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (configMapKey.isEmpty()) {
            return ValidationInfo("ConfigMap key must not be empty.", keyField)
        }
        if (selectedTargets.isEmpty()) {
            return ValidationInfo("Select at least one target ConfigMap.")
        }
        return null
    }

    private fun refreshTargetLabels() {
        val currentKey = keyField.text.trim()
        for ((target, cb) in targetCheckBoxes) {
            val match = findExistingMatch(target, currentKey)
            cb.text = buildTargetLabel(target, match)
        }
    }

    private fun findExistingMatch(target: ConfigMapTarget, candidateKey: String): Pair<String, String>? {
        if (candidateKey.isEmpty()) return null
        target.existingKeys[candidateKey]?.let { return candidateKey to it }
        val relaxed = NavigatorSettings.getInstance(project).enableRelaxedBinding
        if (relaxed) {
            for ((existingKey, preview) in target.existingKeys) {
                if (PropertyKeyNormalizer.matches(existingKey, candidateKey)) {
                    return existingKey to preview
                }
            }
        }
        return null
    }

    private fun buildTargetLabel(target: ConfigMapTarget, existingMatch: Pair<String, String>?): String {
        val pathDisplay = relativeFilePath(target)
        val base = "${target.displayName()}  ($pathDisplay)"
        return if (existingMatch != null) {
            val (matchedKey, preview) = existingMatch
            val valInfo = if (preview.isNotEmpty()) " = \"$preview\"" else ""
            if (matchedKey == keyField.text.trim()) {
                "$base  [exists$valInfo — will update]"
            } else {
                "$base  [matched by $matchedKey$valInfo]"
            }
        } else {
            base
        }
    }

    private fun relativeFilePath(target: ConfigMapTarget): String {
        val baseDir = project.basePath?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
        if (baseDir != null) {
            val rel = VfsUtilCore.getRelativePath(target.virtualFile, baseDir)
            if (rel != null) return rel
        }
        return target.virtualFile.name
    }

    private fun formatKey(rawKey: String, upperSnake: Boolean): String {
        return if (upperSnake) PropertyKeyNormalizer.toEnvVarKey(rawKey) else rawKey.trim()
    }
}
