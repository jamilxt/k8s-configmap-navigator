package com.jamilxt.k8sconfigmap.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker

@Service(Service.Level.PROJECT)
@State(
    name = "K8sConfigMapNavigatorSettings",
    storages = [Storage("k8sConfigMapNavigator.xml")]
)
class NavigatorSettings : PersistentStateComponent<NavigatorSettings.State> {

    data class State(
        var externalConfigMapPaths: MutableList<String> = mutableListOf(),
        var externalProjectPaths: MutableList<String> = mutableListOf(),
        var matchApplicationPropertyOverrides: Boolean = true,
        var matchSystemGetEnv: Boolean = true,
        var enableRelaxedBinding: Boolean = true
    )

    private var state = State()
    val modificationTracker = SimpleModificationTracker()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        modificationTracker.incModificationCount()
    }

    var externalConfigMapPaths: List<String>
        get() = state.externalConfigMapPaths.toList()
        set(value) {
            state.externalConfigMapPaths = value.filter { it.isNotBlank() }.distinct().toMutableList()
            modificationTracker.incModificationCount()
        }

    var externalProjectPaths: List<String>
        get() = state.externalProjectPaths.toList()
        set(value) {
            state.externalProjectPaths = value.filter { it.isNotBlank() }.distinct().toMutableList()
            modificationTracker.incModificationCount()
        }

    var matchApplicationPropertyOverrides: Boolean
        get() = state.matchApplicationPropertyOverrides
        set(value) {
            state.matchApplicationPropertyOverrides = value
            modificationTracker.incModificationCount()
        }

    var matchSystemGetEnv: Boolean
        get() = state.matchSystemGetEnv
        set(value) {
            state.matchSystemGetEnv = value
            modificationTracker.incModificationCount()
        }

    var enableRelaxedBinding: Boolean
        get() = state.enableRelaxedBinding
        set(value) {
            state.enableRelaxedBinding = value
            modificationTracker.incModificationCount()
        }

    companion object {
        fun getInstance(project: Project): NavigatorSettings = project.service()
    }
}
