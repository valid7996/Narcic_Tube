package com.narcictub.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.narcictub.app.domain.model.MediaVariant

/**
 * Plain-View widgets for the overlay bubble. Built entirely in code (no
 * layout/drawable XML) so the floating window has no dependency on Compose's
 * Lifecycle/ViewModelStore/SavedState plumbing, which an overlay Service —
 * unlike an Activity — does not provide out of the box.
 */
internal object BubbleViews {

    private fun Context.dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedRect(context: Context, color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(16).toFloat()
        setColor(color)
    }

    /** The 56dp draggable circle. [progressBar] is toggled visible while resolving. */
    class Bubble(context: Context) {
        val root = FrameLayout(context)
        val progressBar: ProgressBar

        init {
            val size = context.dp(56)
            root.layoutParams = ViewGroup.LayoutParams(size, size)
            root.background = circle(Color.parseColor("#2962FF"))
            root.elevation = context.dp(6).toFloat()

            val icon = TextView(context).apply {
                text = "\u2913" // download glyph, no drawable resource needed
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
            }
            root.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            progressBar = ProgressBar(context).apply {
                isIndeterminate = true
                visibility = View.GONE
            }
            val pad = context.dp(6)
            root.addView(
                progressBar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(pad, pad, pad, pad)
                },
            )
        }

        fun showBusy(busy: Boolean) {
            progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        }

        fun showState(color: Int) {
            root.background = circle(color)
        }
    }

    /** The expanded card: title, one line of status/quality choices, action row. */
    class Panel(context: Context, maxWidthDp: Int = 260) {
        val root = LinearLayout(context)
        val titleView: TextView
        val statusView: TextView
        val qualityRow: LinearLayout
        val downloadButton: Button
        val closeButton: Button
        val openInAppButton: Button

        init {
            root.orientation = LinearLayout.VERTICAL
            root.background = roundedRect(context, Color.parseColor("#FF1E1E1E"))
            val pad = context.dp(12)
            root.setPadding(pad, pad, pad, pad)
            root.layoutParams = ViewGroup.LayoutParams(context.dp(maxWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT)

            titleView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
            }
            root.addView(titleView)

            statusView = TextView(context).apply {
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
                setPadding(0, context.dp(4), 0, context.dp(4))
            }
            root.addView(statusView)

            qualityRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            root.addView(
                android.widget.HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(qualityRow)
                },
            )

            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, context.dp(8), 0, 0)
            }
            openInAppButton = smallButton(context, "Open app")
            closeButton = smallButton(context, "Close")
            downloadButton = smallButton(context, "Download")
            actionRow.addView(openInAppButton)
            actionRow.addView(closeButton)
            actionRow.addView(downloadButton)
            root.addView(actionRow)
        }

        private fun smallButton(context: Context, text: String) = Button(context).apply {
            this.text = text
            textSize = 12f
            setPadding(context.dp(8), 0, context.dp(8), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
        }

        fun renderVariants(variants: List<MediaVariant>, selected: String?, onPick: (MediaVariant) -> Unit) {
            val context = qualityRow.context
            qualityRow.removeAllViews()
            variants.forEach { variant ->
                val label = variant.qualityLabel ?: if (variant.height == null && variant.width == null) "Audio" else "—"
                val chip = smallButton(context, label).apply {
                    isSelected = variant.downloadUrl == selected
                    alpha = if (isSelected) 1f else 0.6f
                    setOnClickListener { onPick(variant) }
                }
                qualityRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = context.dp(6)
                })
            }
        }
    }
}
