package com.conza.outpost.ai

import com.conza.outpost.data.Chunk
import java.io.File

/** Plain UTF-8 readings downloaded into the books folder. */
object TextPassages {
    const val TYPE = "text"

    /** Changes when a book is replaced at the same path. */
    fun versionId(file: File): Long = (file.absolutePath.hashCode().toLong() shl 32) xor file.lastModified() xor file.length()

    fun chunks(path: String, title: String, text: String, versionId: Long = path.hashCode().toLong()): List<Chunk> =
        TextTools.chunk(text, 800, 100).mapIndexed { index, passage ->
            Chunk(
                sourceType = TYPE,
                sourceId = versionId,
                sourceTitle = title,
                position = index,
                text = passage,
                sourceRef = path,
            )
        }

    fun source(chunk: Chunk): Source = Source(TYPE, chunk.sourceTitle, "Downloaded reading", path = chunk.sourceRef)
}
