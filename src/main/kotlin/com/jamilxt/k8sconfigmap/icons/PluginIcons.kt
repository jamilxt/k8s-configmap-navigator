package com.jamilxt.k8sconfigmap.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object PluginIcons {
    @JvmField
    val ConfigMap: Icon = IconLoader.getIcon("/icons/configmap.svg", PluginIcons::class.java)

    @JvmField
    val ConfigMapAdd: Icon = IconLoader.getIcon("/icons/configmap_add.svg", PluginIcons::class.java)

    @JvmField
    val Spring: Icon = IconLoader.getIcon("/icons/spring.svg", PluginIcons::class.java)

    @JvmField
    val Framework: Icon = IconLoader.getIcon("/icons/framework.svg", PluginIcons::class.java)

    @JvmField
    val FrameworkUsed: Icon = IconLoader.getIcon("/icons/framework_used.svg", PluginIcons::class.java)

    @JvmField
    val Library: Icon = IconLoader.getIcon("/icons/library.svg", PluginIcons::class.java)

    @JvmField
    val LibraryUsed: Icon = IconLoader.getIcon("/icons/library_used.svg", PluginIcons::class.java)
}
