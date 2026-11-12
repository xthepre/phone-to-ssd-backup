package com.backup.phonetossd.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.backup.phonetossd.R
import com.backup.phonetossd.model.CheckState
import com.backup.phonetossd.model.TargetSpec
import com.backup.phonetossd.model.TreeNode
import java.io.File

class TreeAdapter(
    private val rootNodes: MutableList<TreeNode>,
    private val onNodeExpanded: (TreeNode) -> Unit
) : RecyclerView.Adapter<TreeAdapter.ViewHolder>() {

    private val visibleNodes = mutableListOf<TreeNode>()

    init {
        rebuildVisibleList()
    }

    fun rebuildVisibleList() {
        visibleNodes.clear()
        fun addRecursively(node: TreeNode) {
            visibleNodes.add(node)
            if (node.isExpanded) {
                for (child in node.children) {
                    addRecursively(child)
                }
            }
        }
        for (root in rootNodes) {
            addRecursively(root)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tree_node, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = visibleNodes.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = visibleNodes[position]
        holder.bind(node)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewIndent: View = itemView.findViewById(R.id.viewIndent)
        private val imgExpand: ImageView = itemView.findViewById(R.id.imgExpand)
        private val chkSelect: CheckBox = itemView.findViewById(R.id.chkSelect)
        private val tvIcon: TextView = itemView.findViewById(R.id.tvIcon)
        private val tvFolderName: TextView = itemView.findViewById(R.id.tvFolderName)
        private val tvHiddenBadge: TextView = itemView.findViewById(R.id.tvHiddenBadge)

        fun bind(node: TreeNode) {
            val density = itemView.context.resources.displayMetrics.density
            viewIndent.layoutParams.width = (node.level * 18 * density).toInt()

            tvFolderName.text = node.name
            tvHiddenBadge.visibility = if (node.isHidden) View.VISIBLE else View.GONE
            tvIcon.text = if (node.isHidden) "🔒" else "📁"

            // Expand icon
            if (node.isFolder) {
                imgExpand.visibility = View.VISIBLE
                imgExpand.rotation = if (node.isExpanded) 180f else 0f
                imgExpand.setOnClickListener {
                    node.isExpanded = !node.isExpanded
                    if (node.isExpanded && !node.isLoaded) {
                        onNodeExpanded(node)
                    } else {
                        rebuildVisibleList()
                    }
                }
            } else {
                imgExpand.visibility = View.INVISIBLE
            }

            // Checkbox state handling
            chkSelect.setOnCheckedChangeListener(null)
            chkSelect.isChecked = node.checkState == CheckState.CHECKED
            chkSelect.alpha = if (node.checkState == CheckState.PARTIALLY_CHECKED) 0.5f else 1.0f

            chkSelect.setOnClickListener {
                val newState = if (node.checkState == CheckState.CHECKED) CheckState.UNCHECKED else CheckState.CHECKED
                node.checkState = newState
                propagateDown(node, newState)
                propagateUp(node)
                notifyDataSetChanged()
            }
        }
    }

    private fun propagateDown(node: TreeNode, state: Int) {
        for (child in node.children) {
            child.checkState = state
            propagateDown(child, state)
        }
    }

    private fun propagateUp(node: TreeNode) {
        val parent = node.parent ?: return
        var checkedCount = 0
        var partialCount = 0
        val total = parent.children.size

        for (child in parent.children) {
            when (child.checkState) {
                CheckState.CHECKED -> checkedCount++
                CheckState.PARTIALLY_CHECKED -> partialCount++
            }
        }

        parent.checkState = when {
            checkedCount == total -> CheckState.CHECKED
            checkedCount == 0 && partialCount == 0 -> CheckState.UNCHECKED
            else -> CheckState.PARTIALLY_CHECKED
        }

        propagateUp(parent)
    }

    fun selectPreset(presetNames: Set<String>) {
        for (root in rootNodes) {
            if (root.name in presetNames) {
                root.checkState = CheckState.CHECKED
                propagateDown(root, CheckState.CHECKED)
            }
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        for (root in rootNodes) {
            root.checkState = CheckState.CHECKED
            propagateDown(root, CheckState.CHECKED)
        }
        notifyDataSetChanged()
    }

    fun clearAll() {
        for (root in rootNodes) {
            root.checkState = CheckState.UNCHECKED
            propagateDown(root, CheckState.UNCHECKED)
        }
        notifyDataSetChanged()
    }

    fun expandAll() {
        fun expandRecursively(node: TreeNode) {
            node.isExpanded = true
            for (c in node.children) expandRecursively(c)
        }
        for (root in rootNodes) expandRecursively(root)
        rebuildVisibleList()
    }

    fun collapseAll() {
        fun collapseRecursively(node: TreeNode) {
            node.isExpanded = false
            for (c in node.children) collapseRecursively(c)
        }
        for (root in rootNodes) collapseRecursively(root)
        rebuildVisibleList()
    }

    /**
     * Resolves selected targets according to Option 2 Smart Behavior:
     * - Checked nodes: target with recursive = true
     * - PartiallyChecked nodes: target with recursive = false (loose files only) + recurse into children
     */
    fun getSelectedTargets(): List<TargetSpec> {
        val list = mutableListOf<TargetSpec>()

        fun collect(node: TreeNode) {
            when (node.checkState) {
                CheckState.CHECKED -> {
                    list.add(TargetSpec(node.relPath, recursive = true))
                }
                CheckState.PARTIALLY_CHECKED -> {
                    // Option 2: Loose files in this parent folder
                    list.add(TargetSpec(node.relPath, recursive = false))
                    for (child in node.children) {
                        collect(child)
                    }
                }
            }
        }

        for (root in rootNodes) {
            collect(root)
        }

        return list
    }
}
