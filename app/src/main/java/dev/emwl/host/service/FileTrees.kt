package dev.emwl.host.service

import dev.emwl.host.domain.DomainException
import java.io.File

/** Small file-tree helpers shared by deploys and revisions; paths stay relative to the root. */
internal object FileTrees {

    fun read(root: File): Map<String, ByteArray> {
        if (!root.isDirectory) return emptyMap()
        return root.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }
    }

    fun copy(from: File, to: File) {
        if (!from.isDirectory) {
            to.mkdirs()
            return
        }
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val target = File(to, file.relativeTo(from).invariantSeparatorsPath)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
        to.mkdirs()
    }

    fun write(root: File, files: Map<String, ByteArray>) {
        root.mkdirs()
        files.forEach { (path, bytes) ->
            val target = File(root, path)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    /**
     * Moves the current tree aside and renames [staged] into place. Both directories share
     * a parent, so each step is a rename on one filesystem; the returned backup is the
     * handle needed to undo the switch.
     */
    fun install(staged: File, target: File): File? {
        val backup = File(target.parentFile, "${target.name}.previous-${System.currentTimeMillis()}")
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            throw DomainException("REVISION_FAILED", "cannot move the current content aside")
        }
        if (!staged.renameTo(target)) {
            if (hadTarget) backup.renameTo(target)
            throw DomainException("REVISION_FAILED", "cannot install the staged content")
        }
        return if (hadTarget) backup else null
    }

    fun revert(target: File, backup: File?) {
        target.deleteRecursively()
        backup?.renameTo(target)
    }
}
