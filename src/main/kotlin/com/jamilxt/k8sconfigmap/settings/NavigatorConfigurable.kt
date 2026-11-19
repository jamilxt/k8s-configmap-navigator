package com.jamilxt.k8sconfigmap.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.FileContentUtilCore
import javax.swing.DefaultListModel
import javax.swing.JPanel

class NavigatorConfigurable(private val project: Project) : BoundConfigurable("K8s ConfigMap Navigator") {

    private val settings: NavigatorSettings
        get() = NavigatorSettings.getInstance(project)

    private val configMapListModel = DefaultListModel<String>()
    private val projectPathsListModel = DefaultListModel<String>()

    private var matchOverrides = true
    private var matchSystemGetEnv = true
    private var enableRelaxedBinding = true

    override fun createPanel(): DialogPanel {
        resetModels()

        return panel {
            group("Matching Options") {
                row {
                    checkBox("Enable relaxed binding (e.g. MY_APP_TIMEOUT <-> my.app.timeout, my.app.connect-timeout, connectTimeout)")
                        .bindSelected(::enableRelaxedBinding)
                }
                row {
                    checkBox("Match direct YAML / .properties config overrides (e.g. SPRING_DATASOURCE_URL -> spring.datasource.url)")
                        .bindSelected(::matchOverrides)
                }
                row {
                    checkBox("Match environment variable API calls (System.getenv, process.env, os.getenv, os.Getenv, etc.)")
                        .bindSelected(::matchSystemGetEnv)
                }
            }

            group("External Kubernetes ConfigMap Paths (Multi-Repo)") {
                row {
                    comment("Add external directories or YAML files containing Kubernetes ConfigMaps outside this project.")
                }
                row {
                    cell(createPathListPanel(configMapListModel, "Select ConfigMap Directory or File", allowFiles = true))
                        .align(AlignX.FILL)
                }
            }

            group("External Application Project Paths (Optional)") {
                row {
                    comment("When viewing a standalone Kubernetes/GitOps repository, add external application project directories to navigate into.")
                }
                row {
                    cell(createPathListPanel(projectPathsListModel, "Select Application Project Directory", allowFiles = false))
                        .align(AlignX.FILL)
                }
            }
        }
    }

    private fun createPathListPanel(
        listModel: DefaultListModel<String>,
        chooserTitle: String,
        allowFiles: Boolean
    ): JPanel {
        val list = JBList(listModel)
        list.visibleRowCount = 5
        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val descriptor = if (allowFiles) {
                    FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                        .withTitle(chooserTitle)
                } else {
                    FileChooserDescriptorFactory.createMultipleFoldersDescriptor()
                        .withTitle(chooserTitle)
                }
                val chosen = FileChooser.chooseFiles(descriptor, project, null)
                for (vf in chosen) {
                    val path = vf.path
                    if (!listModel.contains(path)) {
                        listModel.addElement(path)
                    }
                }
            }
            .setRemoveAction {
                val selectedIndices = list.selectedIndices
                for (i in selectedIndices.reversed()) {
                    listModel.remove(i)
                }
            }
            .disableUpDownActions()
            .createPanel()
    }

    private fun resetModels() {
        configMapListModel.clear()
        settings.externalConfigMapPaths.forEach { configMapListModel.addElement(it) }

        projectPathsListModel.clear()
        settings.externalProjectPaths.forEach { projectPathsListModel.addElement(it) }

        matchOverrides = settings.matchApplicationPropertyOverrides
        matchSystemGetEnv = settings.matchSystemGetEnv
        enableRelaxedBinding = settings.enableRelaxedBinding
    }

    override fun isModified(): Boolean {
        val currentCm = (0 until configMapListModel.size()).map { configMapListModel.getElementAt(it) }
        val currentProj = (0 until projectPathsListModel.size()).map { projectPathsListModel.getElementAt(it) }
        return super.isModified() ||
            currentCm != settings.externalConfigMapPaths ||
            currentProj != settings.externalProjectPaths ||
            matchOverrides != settings.matchApplicationPropertyOverrides ||
            matchSystemGetEnv != settings.matchSystemGetEnv ||
            enableRelaxedBinding != settings.enableRelaxedBinding
    }

    override fun apply() {
        super.apply()
        settings.externalConfigMapPaths = (0 until configMapListModel.size()).map { configMapListModel.getElementAt(it) }
        settings.externalProjectPaths = (0 until projectPathsListModel.size()).map { projectPathsListModel.getElementAt(it) }
        settings.matchApplicationPropertyOverrides = matchOverrides
        settings.matchSystemGetEnv = matchSystemGetEnv
        settings.enableRelaxedBinding = enableRelaxedBinding
        val openFiles = FileEditorManager.getInstance(project).openFiles
        if (openFiles.isNotEmpty()) {
            FileContentUtilCore.reparseFiles(openFiles.toList())
        }
    }

    override fun reset() {
        resetModels()
        super.reset()
    }
}
