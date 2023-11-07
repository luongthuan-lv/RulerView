package com.lvt.rulerview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val TOUCH_SLOP: Int
    private val MIN_FLING_VELOCITY: Int
    private val MAX_FLING_VELOCITY: Int
    private var bgColor = 0
    private var gradationColor = 0
    private var shortLineWidth = 0f
    private var longLineWidth = 0f
    private var shortGradationLen = 0f
    private var longGradationLen = 0f
    private var textColor = 0
    private var textSize = 0f
    private var indicatorLineColor = 0
    private var indicatorLineWidth = 0f
    private var indicatorLineLen = 0f
    var minValue = 0f
        private set
    var maxValue = 0f
        private set
    private var currentValue = 0f
    private var gradationUnit = 0f
    private var numberPerCount = 0
    private var gradationGap = 0f
    private var gradationNumberGap = 0f
    private var mMinNumber = 0
    private var mMaxNumber = 0
    private var mCurrentNumber = 0
    private var mNumberRangeDistance = 0f
    private var mNumberUnit = 0
    private var mCurrentDistance = 0f
    private var mWidthRangeNumber = 0
    private var mPaint: Paint? = null
    private var mTextPaint: TextPaint? = null
    private var mScroller: Scroller? = null
    private var mVelocityTracker: VelocityTracker? = null
    private var mWidth = 0
    private var mHalfWidth = 0
    private var mDownX = 0
    private var mLastX = 0
    private var mLastY = 0
    private var isMoved = false
    private var mValueChangedListener: OnValueChangedListener? = null

    interface OnValueChangedListener {
        fun onValueChanged(value: Float)
    }


    private fun initAttrs(context: Context, attrs: AttributeSet?) {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.RulerView)
        bgColor = ta.getColor(R.styleable.RulerView_bgColor, Color.parseColor("#1f1f1f"))
        gradationColor = ta.getColor(R.styleable.RulerView_gradationColor, Color.LTGRAY)
        shortLineWidth =
            ta.getDimension(R.styleable.RulerView_rv_shortLineWidth, dp2px(1f).toFloat())
        shortGradationLen =
            ta.getDimension(R.styleable.RulerView_rv_shortGradationLen, dp2px(16f).toFloat())
        longGradationLen =
            ta.getDimension(R.styleable.RulerView_rv_longGradationLen, shortGradationLen * 2)
        longLineWidth = ta.getDimension(R.styleable.RulerView_rv_longLineWidth, shortLineWidth * 2)
        textColor = ta.getColor(R.styleable.RulerView_textColor, Color.BLACK)
        textSize = ta.getDimension(R.styleable.RulerView_textSize, sp2px(14f).toFloat())
        indicatorLineColor =
            ta.getColor(R.styleable.RulerView_indicatorLineColor, Color.parseColor("#2b99d0"))
        indicatorLineWidth =
            ta.getDimension(R.styleable.RulerView_indicatorLineWidth, dp2px(3f).toFloat())
        indicatorLineLen =
            ta.getDimension(R.styleable.RulerView_rv_indicatorLineLen, dp2px(35f).toFloat())
        minValue = ta.getFloat(R.styleable.RulerView_rv_minValue, 0f)
        maxValue = ta.getFloat(R.styleable.RulerView_rv_maxValue, 100f)
        currentValue = ta.getFloat(R.styleable.RulerView_rv_currentValue, 0f)
        gradationUnit = ta.getFloat(R.styleable.RulerView_rv_gradationUnit, .1f)
        numberPerCount = ta.getInt(R.styleable.RulerView_rv_numberPerCount, 10)
        gradationGap = ta.getDimension(R.styleable.RulerView_rv_gradationGap, dp2px(10f).toFloat())
        gradationNumberGap =
            ta.getDimension(R.styleable.RulerView_rv_gradationNumberGap, dp2px(8f).toFloat())
        ta.recycle()
    }

    private fun init(context: Context) {
        mPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        mPaint?.strokeWidth = shortLineWidth
        mTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        mTextPaint?.textSize = textSize
        mTextPaint?.color = textColor
        mScroller = Scroller(context)
    }

    private fun convertValue2Number() {
        mMinNumber = (minValue * 10).toInt()
        mMaxNumber = (maxValue * 10).toInt()
        mCurrentNumber = (currentValue * 10).toInt()
        mNumberUnit = (gradationUnit * 10).toInt()
        mCurrentDistance = (mCurrentNumber - mMinNumber) / mNumberUnit * gradationGap
        mNumberRangeDistance = (mMaxNumber - mMinNumber) / mNumberUnit * gradationGap
        if (mWidth != 0) {
            mWidthRangeNumber = (mWidth / gradationGap * mNumberUnit).toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        mWidth = calculateSize(true, widthMeasureSpec)
        val mHeight = calculateSize(false, heightMeasureSpec)
        mHalfWidth = mWidth shr 1
        if (mWidthRangeNumber == 0) {
            mWidthRangeNumber = (mWidth / gradationGap * mNumberUnit).toInt()
        }
        setMeasuredDimension(mWidth, mHeight)
    }

    private fun calculateSize(isWidth: Boolean, spec: Int): Int {
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        var realSize = size
        when (mode) {
            MeasureSpec.EXACTLY -> {}
            MeasureSpec.AT_MOST -> if (!isWidth) {
                val defaultContentSize = dp2px(80f)
                realSize = min(realSize, defaultContentSize)
            }
            MeasureSpec.UNSPECIFIED -> {}
            else -> {}
        }
        logD("isWidth=%b, mode=%d, size=%d, realSize=%d", isWidth, mode, size, realSize)
        return realSize
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        val x = event.x.toInt()
        val y = event.y.toInt()
        logD("onTouchEvent: action=%d", action)
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain()
        }
        mVelocityTracker!!.addMovement(event)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                mScroller?.forceFinished(true)
                mDownX = x
                isMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - mLastX
                if (!isMoved) {
                    val dy = y - mLastY
                    if (abs(dx) < abs(dy) || abs(x - mDownX) < TOUCH_SLOP) {
                        return false
                    }
                    isMoved = true
                }
                mCurrentDistance += -dx.toFloat()
                calculateValue()
            }
            MotionEvent.ACTION_UP -> {
                mVelocityTracker!!.computeCurrentVelocity(1000, MAX_FLING_VELOCITY.toFloat())
                val xVelocity = mVelocityTracker?.xVelocity?.toInt()
                if (abs(xVelocity!!) >= MIN_FLING_VELOCITY) {
                    mScroller?.fling(
                        mCurrentDistance.toInt(), 0, -xVelocity, 0,
                        0, mNumberRangeDistance.toInt(), 0, 0
                    )
                    invalidate()
                } else {
                    scrollToGradation()
                }
            }
            else -> {}
        }
        mLastX = x
        mLastY = y
        return true
    }

    private fun calculateValue() {
        mCurrentDistance = min(max(mCurrentDistance, 0f), mNumberRangeDistance)
        mCurrentNumber = mMinNumber + (mCurrentDistance / gradationGap).toInt() * mNumberUnit
        currentValue = mCurrentNumber / 10f
        logD(
            "calculateValue: mCurrentDistance=%f, mCurrentNumber=%d, currentValue=%f",
            mCurrentDistance, mCurrentNumber, currentValue
        )
        if (mValueChangedListener != null) {
            mValueChangedListener?.onValueChanged(currentValue)
        }
        invalidate()
    }

    private fun scrollToGradation() {
        mCurrentNumber = mMinNumber + Math.round(mCurrentDistance / gradationGap) * mNumberUnit
        mCurrentNumber = min(max(mCurrentNumber, mMinNumber), mMaxNumber)
        mCurrentDistance = (mCurrentNumber - mMinNumber) / mNumberUnit * gradationGap
        currentValue = mCurrentNumber / 10f
        logD(
            "scrollToGradation: mCurrentDistance=%f, mCurrentNumber=%d, currentValue=%f",
            mCurrentDistance, mCurrentNumber, currentValue
        )
        if (mValueChangedListener != null) {
            mValueChangedListener?.onValueChanged(currentValue)
        }
        invalidate()
    }

    override fun computeScroll() {
        if (mScroller?.computeScrollOffset() == true) {
            if (mScroller?.currX != mScroller?.finalX) {
                mCurrentDistance = mScroller?.currX?.toFloat()!!
                calculateValue()
            } else {
                scrollToGradation()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        drawGradation(canvas)
        drawIndicator(canvas)
    }

    private fun drawGradation(canvas: Canvas) {
        mPaint?.color = gradationColor
        mPaint?.strokeWidth = shortLineWidth
        // canvas.drawLine(0, shortLineWidth * .5f, mWidth, 0, mPaint);
        var startNum =
            (mCurrentDistance.toInt() - mHalfWidth) / gradationGap.toInt() * mNumberUnit + mMinNumber
        val expendUnit = mNumberUnit shl 1
        startNum -= expendUnit
        if (startNum < mMinNumber) {
            startNum = mMinNumber
        }
        var rightMaxNum = startNum + expendUnit + mWidthRangeNumber + expendUnit
        if (rightMaxNum > mMaxNumber) {
            rightMaxNum = mMaxNumber
        }
        var distance =
            mHalfWidth - (mCurrentDistance - (startNum - mMinNumber) / mNumberUnit * gradationGap)
        val perUnitCount = mNumberUnit * numberPerCount
        logD(
            "drawGradation: startNum=%d, rightNum=%d, perUnitCount=%d",
            startNum, rightMaxNum, perUnitCount
        )
        while (startNum <= rightMaxNum) {
            logD("drawGradation: startNum=%d", startNum)
            if (startNum % perUnitCount == 0) {
                mPaint?.strokeWidth = longLineWidth
                canvas.drawLine(
                    distance,
                    gradationNumberGap + textSize + (indicatorLineLen - longGradationLen) / 2,
                    distance,
                    gradationNumberGap + textSize + longGradationLen + (indicatorLineLen - longGradationLen) / 2,
                    mPaint!!
                )
                val fNum = startNum / 10f
                var text = java.lang.Float.toString(fNum)
                logD("drawGradation: text=%s", text)
                if (text.endsWith(".0")) {
                    text = text.substring(0, text.length - 2)
                }
                val textWidth = mTextPaint?.measureText(text)
                canvas.drawText(text, distance - textWidth!! * .5f, textSize, mTextPaint!!)
            } else {
                mPaint?.strokeWidth = shortLineWidth
                canvas.drawLine(
                    distance,
                    gradationNumberGap + textSize + (indicatorLineLen - shortGradationLen) / 2,
                    distance,
                    gradationNumberGap + textSize + shortGradationLen + (indicatorLineLen - shortGradationLen) / 2,
                    mPaint!!
                )
            }
            startNum += mNumberUnit
            distance += gradationGap
        }
    }

    private fun drawIndicator(canvas: Canvas) {
        mPaint?.color = indicatorLineColor
        mPaint?.strokeWidth = indicatorLineWidth
        mPaint?.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            mHalfWidth.toFloat(),
            gradationNumberGap + textSize,
            mHalfWidth.toFloat(),
            gradationNumberGap + textSize + indicatorLineLen,
            mPaint!!
        )
        mPaint?.strokeCap = Paint.Cap.BUTT
    }

    private fun dp2px(dp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
            .toInt()
    }

    private fun sp2px(sp: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            .toInt()
    }

    private fun logD(format: String, vararg args: Any) {
            Log.d("GradationView", String.format("zjun@$format", *args))
    }

    fun setCurrentValue(currentValue: Float) {
        require(!(currentValue < minValue || currentValue > maxValue)) {
            String.format(
                "The currentValue of %f is out of range: [%f, %f]",
                currentValue, minValue, maxValue
            )
        }
        if (!mScroller?.isFinished!!) {
            mScroller?.forceFinished(true)
        }
        this.currentValue = currentValue
        mCurrentNumber = (this.currentValue * 10).toInt()
        val newDistance = (mCurrentNumber - mMinNumber) / mNumberUnit * gradationGap
        val dx = (newDistance - mCurrentDistance).toInt()
        val duration = dx * 2000 / mNumberRangeDistance.toInt()
        mScroller?.startScroll(mCurrentDistance.toInt(), 0, dx, duration)
        postInvalidate()
    }

    fun getCurrentValue(): Float {
        return currentValue
    }

    fun setValue(minValue: Float, maxValue: Float, curValue: Float, unit: Float, perCount: Int) {
        require(!(minValue > maxValue || curValue < minValue || curValue > maxValue)) {
            String.format(
                "The given values are invalid, check firstly: " +
                        "minValue=%f, maxValue=%f, curValue=%s", minValue, maxValue, curValue
            )
        }
        if (!mScroller?.isFinished!!) {
            mScroller?.forceFinished(true)
        }
        this.minValue = minValue
        this.maxValue = maxValue
        currentValue = curValue
        gradationUnit = unit
        numberPerCount = perCount
        convertValue2Number()
        if (mValueChangedListener != null) {
            mValueChangedListener?.onValueChanged(currentValue)
        }
        postInvalidate()
    }

    fun setOnValueChangedListener(listener: OnValueChangedListener?) {
        mValueChangedListener = listener
    }

    companion object {
    }

    init {
        initAttrs(context, attrs)
        val viewConfiguration = ViewConfiguration.get(context)
        TOUCH_SLOP = viewConfiguration.scaledTouchSlop
        MIN_FLING_VELOCITY = viewConfiguration.scaledMinimumFlingVelocity
        MAX_FLING_VELOCITY = viewConfiguration.scaledMaximumFlingVelocity
        convertValue2Number()
        init(context)
    }
}