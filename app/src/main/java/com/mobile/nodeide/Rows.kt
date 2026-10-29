package com.mobile.nodeide

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Compact clickable rows used for the editor's project tree. */
class Rows(private val click: (Int) -> Unit) : RecyclerView.Adapter<Rows.Holder>() {

    private val items = mutableListOf<String>()

    fun set(data: List<String>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val text = TextView(context).apply {
            setTextColor(Color.rgb(220, 226, 242))
            textSize = 13f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(Color.rgb(17, 24, 42))
            }
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (5 * density).toInt()
                rightMargin = (5 * density).toInt()
                topMargin = (3 * density).toInt()
                bottomMargin = (3 * density).toInt()
            }
        }
        return Holder(text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
        holder.text.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) click(current)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
}
