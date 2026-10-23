package com.denova.novara.core.editor

import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta

sealed interface MergeResult {
    /** 无冲突，已自动合并。 */
    data class Clean(val text: String) : MergeResult

    /** 有冲突，text 含 `<<<<<<<` / `=======` / `>>>>>>>` 标记，需人工消解。 */
    data class Conflicts(val text: String, val conflictCount: Int) : MergeResult
}

/**
 * 行级三方合并（diff3）：base 为双方共同起点，ours 为本地编辑，theirs 为服务端当前内容。
 * 不重叠的修改分别应用；相同修改去重；重叠且不同的修改产生冲突标记。
 */
object ThreeWayMerge {

    fun merge(base: String, ours: String, theirs: String): MergeResult {
        val b = base.split('\n')
        val o = ours.split('\n')
        val t = theirs.split('\n')
        val dO = DiffUtils.diff(b, o).deltas
        val dT = DiffUtils.diff(b, t).deltas

        val out = mutableListOf<String>()
        var conflicts = 0
        var baseIdx = 0
        var i = 0
        var j = 0

        fun copyBaseUntil(pos: Int) {
            while (baseIdx < pos) {
                out.add(b[baseIdx])
                baseIdx++
            }
        }

        fun applyDelta(delta: AbstractDelta<String>) {
            out.addAll(delta.target.lines)
            baseIdx = delta.source.position + delta.source.size()
        }

        while (i < dO.size || j < dT.size) {
            val deltaO = dO.getOrNull(i)
            val deltaT = dT.getOrNull(j)

            when {
                deltaO == null -> {
                    copyBaseUntil(deltaT!!.source.position)
                    applyDelta(deltaT)
                    j++
                }
                deltaT == null -> {
                    copyBaseUntil(deltaO.source.position)
                    applyDelta(deltaO)
                    i++
                }
                else -> {
                    val startO = deltaO.source.position
                    val endO = startO + deltaO.source.size()
                    val startT = deltaT.source.position
                    val endT = startT + deltaT.source.size()

                    when {
                        endO <= startT -> {
                            copyBaseUntil(startO)
                            applyDelta(deltaO)
                            i++
                        }
                        endT <= startO -> {
                            copyBaseUntil(startT)
                            applyDelta(deltaT)
                            j++
                        }
                        else -> {
                            copyBaseUntil(minOf(startO, startT))
                            val identical = deltaO.source.lines == deltaT.source.lines &&
                                deltaO.target.lines == deltaT.target.lines
                            if (identical) {
                                out.addAll(deltaO.target.lines)
                            } else {
                                out.add("<<<<<<< 本地修改")
                                out.addAll(deltaO.target.lines)
                                out.add("=======")
                                out.addAll(deltaT.target.lines)
                                out.add(">>>>>>> 服务器修改")
                                conflicts++
                            }
                            baseIdx = maxOf(endO, endT)
                            i++
                            j++
                        }
                    }
                }
            }
        }
        copyBaseUntil(b.size)

        val text = out.joinToString("\n")
        return if (conflicts == 0) MergeResult.Clean(text) else MergeResult.Conflicts(text, conflicts)
    }
}
