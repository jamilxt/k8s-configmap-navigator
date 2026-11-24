package com.jamilxt.k8sconfigmap.actions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer
import com.jamilxt.k8sconfigmap.model.ConfigMapTarget
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

object ConfigMapYamlWriter {

    enum class WriteStatus {
        ADDED,
        UPDATED,
        FAILED
    }

    data class WriteResult(
        val status: WriteStatus,
        val target: ConfigMapTarget,
        val keyValue: YAMLKeyValue? = null
    )

    fun addOrUpdateKey(
        project: Project,
        target: ConfigMapTarget,
        key: String,
        value: String
    ): WriteResult {
        val docElement = target.documentPointer.element ?: return WriteResult(WriteStatus.FAILED, target)
        val yamlFile = docElement.containingFile ?: return WriteResult(WriteStatus.FAILED, target)
        val vFile = yamlFile.virtualFile ?: target.virtualFile
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val document = psiDocumentManager.getDocument(yamlFile)
            ?: FileDocumentManager.getInstance().getDocument(vFile)
            ?: return WriteResult(WriteStatus.FAILED, target)

        var status = WriteStatus.FAILED
        val formattedKey = formatYamlKey(key.trim())
        val formattedValue = formatYamlDoubleQuotedValue(value)

        WriteCommandAction.writeCommandAction(project, yamlFile)
            .withName("Add '$key' to ConfigMap '${target.displayName()}'")
            .run<RuntimeException> {
                psiDocumentManager.commitDocument(document)
                val currentDoc = target.documentPointer.element ?: return@run
                val rootMapping = currentDoc.topLevelValue as? YAMLMapping ?: return@run
                val dataKv = rootMapping.getKeyValueByKey("data")

                if (dataKv != null) {
                    val dataMapping = dataKv.value as? YAMLMapping
                    if (dataMapping != null && dataMapping.keyValues.isNotEmpty()) {
                        val existingKv = dataMapping.getKeyValueByKey(key.trim())
                        if (existingKv != null) {
                            updateExistingKeyValue(document, existingKv, formattedValue)
                            status = WriteStatus.UPDATED
                        } else {
                            val anchorKv = findInsertionAnchor(dataMapping.keyValues.toList(), key.trim())
                            val indent = computeLineIndent(document, anchorKv.textRange.startOffset)
                            val anchorEndOffset = (anchorKv.textRange.endOffset - 1)
                                .coerceAtLeast(anchorKv.textRange.startOffset)
                            val anchorEndLine = document.getLineNumber(anchorEndOffset)
                            val insertOffset = document.getLineEndOffset(anchorEndLine)
                            document.insertString(insertOffset, "\n$indent$formattedKey: $formattedValue")
                            status = WriteStatus.ADDED
                        }
                    } else {
                        val dataIndent = computeLineIndent(document, dataKv.textRange.startOffset)
                        val childIndent = "$dataIndent  "
                        val dataValue = dataKv.value
                        if (dataValue != null && dataValue.text.trim() == "{}") {
                            val colonEnd = findColonEndOffset(document, dataKv)
                            document.replaceString(
                                colonEnd,
                                dataValue.textRange.endOffset,
                                "\n$childIndent$formattedKey: $formattedValue"
                            )
                        } else {
                            val insertOffset = dataKv.textRange.endOffset
                            document.insertString(insertOffset, "\n$childIndent$formattedKey: $formattedValue")
                        }
                        status = WriteStatus.ADDED
                    }
                } else {
                    val rootIndent = computeLineIndent(document, rootMapping.textRange.startOffset)
                    val insertOffset = rootMapping.textRange.endOffset
                    document.insertString(
                        insertOffset,
                        "\n${rootIndent}data:\n${rootIndent}  $formattedKey: $formattedValue"
                    )
                    status = WriteStatus.ADDED
                }

                psiDocumentManager.commitDocument(document)
            }

        if (status == WriteStatus.FAILED) {
            return WriteResult(WriteStatus.FAILED, target)
        }

        val updatedDoc = target.documentPointer.element
        val updatedKv = findKeyValueInDocument(updatedDoc, key.trim())
        return WriteResult(status, target, updatedKv)
    }

    private fun updateExistingKeyValue(
        document: com.intellij.openapi.editor.Document,
        existingKv: YAMLKeyValue,
        formattedValue: String
    ) {
        val existingValue = existingKv.value
        if (existingValue != null) {
            document.replaceString(
                existingValue.textRange.startOffset,
                existingValue.textRange.endOffset,
                formattedValue
            )
        } else {
            val colonEnd = findColonEndOffset(document, existingKv)
            document.replaceString(colonEnd, existingKv.textRange.endOffset, " $formattedValue")
        }
    }

    private fun findColonEndOffset(
        document: com.intellij.openapi.editor.Document,
        kv: YAMLKeyValue
    ): Int {
        val start = kv.textRange.startOffset
        val end = kv.textRange.endOffset
        val text = document.getText(TextRange(start, end))
        val colonIdx = text.indexOf(':')
        return if (colonIdx >= 0) start + colonIdx + 1 else end
    }

    private fun computeLineIndent(document: com.intellij.openapi.editor.Document, offset: Int): String {
        val lineNum = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNum)
        val linePrefix = document.getText(TextRange(lineStart, offset))
        return linePrefix.takeWhile { it == ' ' || it == '\t' }
    }

    private fun findKeyValueInDocument(doc: YAMLDocument?, key: String): YAMLKeyValue? {
        val rootMapping = doc?.topLevelValue as? YAMLMapping ?: return null
        val dataMapping = rootMapping.getKeyValueByKey("data")?.value as? YAMLMapping ?: return null
        return dataMapping.getKeyValueByKey(key)
    }

    internal fun findInsertionAnchor(
        existingKeyValues: List<YAMLKeyValue>,
        newKey: String
    ): YAMLKeyValue {
        val newTokens = PropertyKeyNormalizer.tokenize(newKey)
        if (newTokens.isEmpty()) return existingKeyValues.last()

        var bestPrefixLen = 0
        var bestAnchor: YAMLKeyValue? = null

        for (kv in existingKeyValues) {
            val existingTokens = PropertyKeyNormalizer.tokenize(kv.keyText)
            val prefixLen = commonPrefixLength(newTokens, existingTokens)
            if (prefixLen >= 1 && prefixLen >= bestPrefixLen) {
                bestPrefixLen = prefixLen
                bestAnchor = kv
            }
        }

        return bestAnchor ?: existingKeyValues.last()
    }

    internal fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        val limit = minOf(a.size, b.size)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    internal fun formatYamlKey(key: String): String {
        val needsQuoting = key.isEmpty() ||
            key.any { it.isWhitespace() || it == ':' || it == '#' || it == '"' || it == '\'' } ||
            key.first() in charArrayOf('-', '?', '[', ']', '{', '}', ',', '&', '*', '!', '|', '>', '%', '@', '`')
        return if (needsQuoting) formatYamlDoubleQuotedValue(key) else key
    }

    internal fun formatYamlDoubleQuotedValue(value: String): String {
        val escaped = buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }
}
