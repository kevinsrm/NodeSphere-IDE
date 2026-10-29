package com.mobile.nodeide

import android.graphics.Color
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter genérico de linhas de texto usado nas listas de projetos,
 * plugins e na árvore de arquivos do editor.
 */
class Rows(private val click: (Int) -> Unit) : RecyclerView.Adapter<Rows.Holder>() {

    private val items = mutableListOf<String>()

    fun set(data: List<String>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = TextView(parent.context).apply {
            setTextColor(Color.rgb(225, 222, 239))
            textSize = 15f
            setPadding(12, 16, 8, 16)
            setBackgroundColor(Color.rgb(43, 41, 76))
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
