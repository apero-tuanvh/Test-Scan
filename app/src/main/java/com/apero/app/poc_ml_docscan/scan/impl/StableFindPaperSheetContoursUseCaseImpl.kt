package com.apero.core.scan

import android.graphics.Bitmap
import androidx.annotation.FloatRange
import arrow.core.Either
import arrow.core.raise.either
import com.apero.app.poc_ml_docscan.scan.common.model.Size
import com.apero.core.scan.model.SensorRotationDegrees
import com.apero.app.poc_ml_docscan.scan.common.util.AnalyticsReporter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.time.measureTimedValue

internal class StableFindPaperSheetContoursUseCaseImpl(
    private val delegate: FindPaperSheetContoursRealtimeUseCase,
    private val computeContourAreaUseCase: ComputeContourAreaUseCase,
    private val sortContoursUseCase: SortContoursUseCase,
    @FloatRange(from = 0.0, to = 1.0)
    private val coveragePercentageThreshold: Float,
    private val dispatcher: CoroutineDispatcher,
    private val analyticsReporter: AnalyticsReporter,
) : FindPaperSheetContoursRealtimeUseCase by delegate {

    override suspend fun invoke(
        bitmap: Bitmap,
        degrees: SensorRotationDegrees,
        debug: Boolean,
    ): Either<Exception, FindPaperSheetContoursRealtimeUseCase.Contours> = withContext(dispatcher) {
        measureTimedValue {
            compute(bitmap, degrees, debug)
        }
            .also {
                val (value, duration) = it
                val rightValue = value.getOrNull() ?: return@also
                analyticsReporter.reportEvent(
                    "time_model",
                    "tflite_infer_ms" to "${rightValue.inferTime.inWholeMilliseconds}",
                    "opencv_find_coutour_ms" to "${rightValue.findContoursTime.inWholeMilliseconds}",
                    "total_ms" to "${duration.inWholeMilliseconds}",
                )
            }
            .value
    }

    private suspend fun compute(
        bitmap: Bitmap,
        degrees: SensorRotationDegrees,
        debug: Boolean,
    ): Either<Exception, FindPaperSheetContoursRealtimeUseCase.Contours> = either {
        var result = delegate(bitmap, degrees, debug).bind()
        if (result.corners == null) return@either result
        result = result.copy(corners = sortContoursUseCase.invoke(result.corners!!))

        val contourArea = computeContourAreaUseCase(result.corners!!)
        val outputArea = result.outputSize.area

        val coveragePercentage = contourArea / outputArea

        if (coveragePercentage >= coveragePercentageThreshold) {
            return@either result
        }

        result.copy(corners = null)
    }
}

internal val Size.area: Float get() = width * height