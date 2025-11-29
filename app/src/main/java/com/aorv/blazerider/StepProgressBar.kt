package com.aorv.blazerider

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.aorv.blazerider.R

class StepProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    private val steps: List<View>

    var step: Int = 1
        set(value) {
            field = value
            updateSteps()
        }

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.progress_bar, this, true)

        steps = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.step4)
        )

        context.theme.obtainStyledAttributes(attrs, R.styleable.StepProgressBar, 0, 0).apply {
            try {
                step = getInteger(R.styleable.StepProgressBar_step, 1)
            } finally {
                recycle()
            }
        }

        updateSteps()
    }

    private fun updateSteps() {
        steps.forEachIndexed { index, view ->
            view.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (index < step) R.color.red_orange else R.color.gray
                )
            )
        }
    }
}
