package com.envy.dotenv.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which secret fold regions have been manually revealed by the user.
 * These remain expanded even when presentation mode is toggled back on.
 */
@Service(Service.Level.PROJECT)
class PresentationModeState(private val project: Project) : Disposable {

    // file path -> list of range markers
    private val revealedMarkers = ConcurrentHashMap<String, MutableList<RangeMarker>>()

    fun markRevealed(file: VirtualFile, document: Document, startOffset: Int, endOffset: Int) {
        val marker = document.createRangeMarker(startOffset, endOffset)
        revealedMarkers.getOrPut(file.path) { java.util.Collections.synchronizedList(mutableListOf()) }.add(marker)
    }

    fun markAllRevealed(file: VirtualFile, document: Document, regions: List<Pair<Int, Int>>) {
        val markers = revealedMarkers.getOrPut(file.path) { java.util.Collections.synchronizedList(mutableListOf()) }
        for (region in regions) {
            markers.add(document.createRangeMarker(region.first, region.second))
        }
    }

    fun isRevealed(file: VirtualFile, startOffset: Int): Boolean {
        val markers = revealedMarkers[file.path] ?: return false
        markers.removeIf { !it.isValid }
        if (markers.isEmpty()) {
            revealedMarkers.remove(file.path)
            return false
        }
        return markers.any { it.startOffset == startOffset }
    }

    fun clearRevealed(file: VirtualFile) {
        revealedMarkers.remove(file.path)?.forEach { it.dispose() }
    }

    override fun dispose() {
        for (markers in revealedMarkers.values) {
            markers.forEach { it.dispose() }
        }
        revealedMarkers.clear()
    }
}
