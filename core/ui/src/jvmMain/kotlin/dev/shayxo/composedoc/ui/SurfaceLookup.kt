package dev.shayxo.composedoc.ui
import java.awt.Component
import java.awt.Container
fun findSurface(component: Component): EditorSurface? = if (component is EditorSurface) component else (component as? Container)?.components?.firstNotNullOfOrNull(::findSurface)
