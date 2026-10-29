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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** A plugin entry shown in the runtime catalog. */
data class PluginCard(
    val id: String,
    val name: String,
    val description: String,
    val status: String,
    val action: String,
    val icon: Int,
    val accent: Int
)

/** Runtime cards with recognizable brand marks and clear availability state. */
class PluginRows(private val onAction: (String) -> Unit) : RecyclerView.Adapter<PluginRows.Holder>() {

    private val items = mutableListOf<PluginCard>()
    private var density = 1f

    fun set(cards: List<PluginCard>) {
        items.clear()
        items.addAll(cards)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        density = context.resources.displayMetrics.density
        val card = MaterialCardView(context).apply {
            setRadius(dp(18).toFloat())
            cardElevation = 0f
            setStrokeWidth(dp(1))
            setStrokeColor(ColorStateList.valueOf(Color.rgb(42, 52, 75)))
            setCardBackgroundColor(Color.rgb(19, 26, 44))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(15).toFloat()
            }
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        badge.addView(icon, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        header.addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))

        val title = TextView(context).apply {
            setTextColor(Color.rgb(245, 246, 255))
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }
        val status = TextView(context).apply {
            textSize = 9f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val titleAndStatus = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleAndStatus.addView(title)
        val statusParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) }
        titleAndStatus.addView(status, statusParams)
        header.addView(
            titleAndStatus,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        content.addView(header)

        val description = TextView(context).apply {
            setTextColor(Color.rgb(163, 174, 197))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 2
            setPadding(0, dp(12), 0, dp(10))
        }
        content.addView(description)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val platform = TextView(context).apply {
            text = "ANDROID"
            setTextColor(Color.rgb(126, 141, 171))
            textSize = 10f
            letterSpacing = 0.12f
        }
        footer.addView(platform, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = MaterialButton(context).apply {
            isAllCaps = false
            textSize = 12f
            minimumHeight = dp(38)
            setInsetTop(0)
            setInsetBottom(0)
            setPadding(dp(13), 0, dp(13), 0)
            setCornerRadius(dp(12))
            setStrokeWidth(dp(1))
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(Color.rgb(229, 231, 255))
            maxLines = 1
        }
        footer.addView(action, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(38)
        ))
        content.addView(footer)
        card.addView(content)

        return Holder(card, badge, icon, title, status, description, action)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.name
        holder.description.text = item.description
        holder.status.text = item.status.uppercase()
        holder.icon.setImageResource(item.icon)
        holder.badge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.badge, 15).toFloat()
            setColor(item.accent)
        }
        holder.status.setTextColor(item.accent)
        holder.status.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(holder.status, 20).toFloat()
            setColor(withAlpha(item.accent, 30))
            setStroke(dp(holder.status, 1), withAlpha(item.accent, 86))
        }
        holder.action.text = item.action
        holder.action.setStrokeColor(ColorStateList.valueOf(withAlpha(item.accent, 180)))
        holder.action.setOnClickListener { onAction(item.id) }
        holder.card.setOnClickListener { onAction(item.id) }
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        val card: MaterialCardView,
        val badge: FrameLayout,
        val icon: ImageView,
        val title: TextView,
        val status: TextView,
        val description: TextView,
        val action: MaterialButton
    ) : RecyclerView.ViewHolder(card)

    private fun dp(value: Int): Int =
        (value * density).toInt()

    private fun dp(view: android.view.View, value: Int): Int =
        (value * view.resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
