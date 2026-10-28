package com.jev.probe

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jev.probe.core.kb.Contact
import kotlin.math.roundToInt

/** A compact reorder sheet keeps drag gestures away from edit/delete and history actions. */
internal object ContactOrderDialog {
    fun show(context: Context, contacts: List<Contact>, counts: Map<String, Int>,
             onSave: (List<String>) -> Boolean): AlertDialog {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        box.addView(TextView(context).apply {
            text = "按住右侧拖动柄上下移动，也可点箭头调整。点保存后生效；取消不改变顺序。"
            textSize = 13f; setTextColor(Color.parseColor("#53647A"))
            setPadding(0, 0, 0, dp(10))
        })
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(440), (context.resources.displayMetrics.heightPixels * .55f).roundToInt()))
        }
        // AlertDialog measures its content before its final window width is known. On
        // a width change remeasure the weighted rows, including their cached children.
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) list.post {
                for (i in 0 until list.childCount) list.getChildAt(i).requestLayout()
                list.requestLayout()
            }
        }
        val adapter = OrderAdapter(context, contacts, counts)
        list.adapter = adapter
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(recyclerView: RecyclerView, source: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean =
                adapter.move(source.adapterPosition, target.adapterPosition)
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        })
        helper.attachToRecyclerView(list)
        adapter.startDrag = { holder -> helper.startDrag(holder) }
        box.addView(list)
        val dialog = AlertDialog.Builder(context, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle("手动排列联系人").setView(box)
            .setPositiveButton("保存顺序", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (onSave(adapter.ids())) dialog.dismiss()
                else Toast.makeText(context, "顺序保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
        return dialog
    }

    internal class OrderAdapter(private val context: Context, contacts: List<Contact>,
                                private val counts: Map<String, Int>) : RecyclerView.Adapter<OrderAdapter.Holder>() {
        private val items = contacts.toMutableList()
        var startDrag: ((Holder) -> Unit)? = null
        private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        class Holder(val row: LinearLayout, val title: TextView, val count: TextView,
                     val up: TextView, val down: TextView, val handle: TextView) : RecyclerView.ViewHolder(row)
        override fun getItemCount() = items.size
        fun ids() = items.map { it.id }
        fun move(from: Int, to: Int): Boolean {
            if (from !in items.indices || to !in items.indices || from == to) return false
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
            return true
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(4), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.parseColor("#F1F5FA")) }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(context).apply {
                textSize = 15f; setTextColor(Color.parseColor("#26364A")); setTypeface(typeface, Typeface.BOLD)
            }
            val count = TextView(context).apply { textSize = 12f; setTextColor(Color.parseColor("#53647A")) }
            labels.addView(title); labels.addView(count); row.addView(labels)
            fun control(glyph: String) = TextView(context).apply {
                text = glyph; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#355FA8"))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(48))
                isClickable = true; isFocusable = true
            }
            val up = control("↑"); val down = control("↓"); val handle = control("≡")
            row.addView(up); row.addView(down); row.addView(handle)
            val holder = Holder(row, title, count, up, down, handle)
            up.setOnClickListener { move(holder.adapterPosition, holder.adapterPosition - 1) }
            down.setOnClickListener { move(holder.adapterPosition, holder.adapterPosition + 1) }
            handle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    startDrag?.invoke(holder)
                }
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val contact = items[position]
            holder.title.text = contact.name.ifBlank { "（无名）" }
            val apps = contact.apps.map { when (it) {
                "com.tencent.mm" -> "微信"
                "com.tantan.x" -> "牵手"
                "cn.soulapp.android" -> "Soul"
                "com.wepie.ivy" -> "青藤之恋"
                "com.tencent.mobileqq" -> "QQ"
                else -> "其他 App"
            } }.distinct().joinToString("、")
            holder.count.text = "${counts[contact.id] ?: 0} 条记忆${if (apps.isBlank()) "" else " · $apps"}"
            holder.up.contentDescription = "${contact.name}向上移动"
            holder.down.contentDescription = "${contact.name}向下移动"
            holder.handle.contentDescription = "${contact.name}排序拖动柄"
        }
    }
}
