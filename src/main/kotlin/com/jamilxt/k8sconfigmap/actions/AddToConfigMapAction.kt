package com.jamilxt.k8sconfigmap.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.jamilxt.k8sconfigmap.icons.PluginIcons
import com.jamilxt.k8sconfigmap.index.ConfigMapIndexService
import com.jamilxt.k8sconfigmap.index.UsageIndexService
import org.jetbrains.yaml.psi.YAMLKeyValue

class AddToConfigMapAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        if (project == null || editor == null || psiFile == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val offset = editor.caretModel.offset
        val element = findTargetElement(psiFile, offset)
        if (element == null || isInsideConfigMapData(project, element)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val keys = UsageIndexService.getInstance(project).extractKeysAtElement(element, offset)
        if (keys.isEmpty()) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val cmIndex = ConfigMapIndexService.getInstance(project)
        val configMaps = cmIndex.getAllConfigMaps()
        if (configMaps.isNotEmpty() && allKeysExistInAllConfigMaps(project, keys, configMaps)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = true
        e.presentation.icon = PluginIcons.ConfigMapAdd
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val element = findTargetElement(psiFile, offset) ?: return

        val usageIndex = UsageIndexService.getInstance(project)
        val keys = usageIndex.extractKeysAtElement(element, offset)
        if (keys.isEmpty()) return

        val defaultValue = usageIndex.extractDefaultValueAtElement(element, offset)
        showAddDialog(project, keys, defaultValue)
    }

    private fun findTargetElement(psiFile: PsiFile, offset: Int): PsiElement? {
        val atOffset = psiFile.findElementAt(offset)
        if (atOffset != null && atOffset !is PsiWhiteSpace) return atOffset
        if (offset > 0) {
            val before = psiFile.findElementAt(offset - 1)
            if (before != null && before !is PsiWhiteSpace) return before
        }
        return atOffset
    }

    private fun isInsideConfigMapData(project: com.intellij.openapi.project.Project, element: PsiElement): Boolean {
        val yamlKv = PsiTreeUtil.getParentOfType(element, YAMLKeyValue::class.java, false) ?: return false
        return ConfigMapIndexService.getInstance(project).isConfigMapDataKey(yamlKv)
    }

    private fun allKeysExistInAllConfigMaps(
        project: com.intellij.openapi.project.Project,
        keys: List<String>,
        configMaps: List<com.jamilxt.k8sconfigmap.model.ConfigMapTarget>
    ): Boolean {
        val relaxed = com.jamilxt.k8sconfigmap.settings.NavigatorSettings.getInstance(project).enableRelaxedBinding
        return keys.all { key ->
            configMaps.all { cm ->
                cm.existingKeys.keys.any { existing ->
                    if (relaxed) com.jamilxt.k8sconfigmap.core.PropertyKeyNormalizer.matches(existing, key)
                    else existing == key
                }
            }
        }
    }

    companion object {
        fun showAddDialog(
            project: com.intellij.openapi.project.Project,
            keys: List<String>,
            defaultValue: String?
        ) {
            if (keys.isEmpty()) return
            val configMaps = ConfigMapIndexService.getInstance(project).getAllConfigMaps()
            if (configMaps.isEmpty()) {
                Messages.showInfoMessage(
                    project,
                    "No Kubernetes ConfigMap YAML files were found in this project.\n\n" +
                        "You can also configure external ConfigMap directories under:\n" +
                        "Settings → Tools → K8s ConfigMap Navigator.",
                    "Add to Kubernetes ConfigMap"
                )
                return
            }

            val dialog = AddToConfigMapDialog(project, keys, defaultValue, configMaps)
            if (!dialog.showAndGet()) return

            val key = dialog.configMapKey
            val value = dialog.configMapValue
            val selectedTargets = dialog.selectedTargets
            if (key.isEmpty() || selectedTargets.isEmpty()) return

            val results = selectedTargets.map { target ->
                ConfigMapYamlWriter.addOrUpdateKey(project, target, key, value)
            }

            val added = results.filter { it.status == ConfigMapYamlWriter.WriteStatus.ADDED }
            val updated = results.filter { it.status == ConfigMapYamlWriter.WriteStatus.UPDATED }
            val succeeded = added + updated

            if (dialog.openAfterAdd) {
                val first = succeeded.firstOrNull()
                if (first != null) {
                    val navOffset = first.keyValue?.textOffset ?: 0
                    OpenFileDescriptor(project, first.target.virtualFile, navOffset).navigate(true)
                }
            }

            if (succeeded.isNotEmpty()) {
                val summary = buildString {
                    if (added.isNotEmpty()) {
                        append("Added <b>$key</b> to ")
                        append(added.joinToString(", ") { it.target.displayName() })
                    }
                    if (updated.isNotEmpty()) {
                        if (isNotEmpty()) append("; ")
                        append("Updated <b>$key</b> in ")
                        append(updated.joinToString(", ") { it.target.displayName() })
                    }
                }
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("K8s ConfigMap Navigator")
                    ?.createNotification(summary, NotificationType.INFORMATION)
                    ?.notify(project)
            }
        }
    }
}
