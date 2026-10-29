package com.mobile.nodeide

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.io.File

/** Compact project cards for the workspace list. */
class ProjectRows(private val click: (File) -> Unit) : RecyclerView.Adapter<ProjectRows.Holder>() {

    private val projects = mutableListOf<File>()

    fun set(files: List<File>) {
        projects.clear()
        projects.addAll(files)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            setRadius(dp(18).toFloat())
            cardElevation = 0f
            setStrokeWidth(dp(1))
            setStrokeColor(android.content.res.ColorStateList.valueOf(Color.rgb(42, 52, 75)))
            setCardBackgroundColor(Color.rgb(19, 26, 44))
            isClickable = true
            isFocusable = true
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val badge = FrameLayout(context).apply {
            background = rounded(Color.rgb(85, 72, 164), 15)
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_project_folder)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        badge.addView(icon, FrameLayout.LayoutParams(dp(context, 50), dp(context, 50), Gravity.CENTER))
        row.addView(badge, LinearLayout.LayoutParams(dp(context, 50), dp(context, 50)))

        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), 0, dp(8), 0)
        }
        val title = TextView(context).apply {
            setTextColor(Color.rgb(244, 245, 255))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        val subtitle = TextView(context).apply {
            setTextColor(Color.rgb(151, 163, 188))
            textSize = 12f
            maxLines = 1
            setPadding(0, dp(4), 0, 0)
        }
        details.addView(title)
        details.addView(subtitle)
        row.addView(
            details,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(TextView(context).apply {
            text = "›"
            setTextColor(Color.rgb(133, 145, 174))
            textSize = 28f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(context, 24), dp(context, 44)))

        card.addView(row)
        return Holder(card, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val project = projects[position]
        holder.title.text = project.name
        holder.subtitle.text = "Projeto local  ·  ~/projects/${project.name}"
        holder.card.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) click(projects[current])
        }
    }

    override fun getItemCount(): Int = projects.size

    class Holder(
        val card: MaterialCardView,
        val title: TextView,
        val subtitle: TextView
    ) : RecyclerView.ViewHolder(card)

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun android.view.View.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun android.view.View.rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }
}
