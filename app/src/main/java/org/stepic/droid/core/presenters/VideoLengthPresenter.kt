package org.stepic.droid.core.presenters

import org.stepic.droid.concurrency.MainHandler
import org.stepic.droid.core.VideoLengthResolver
import org.stepic.droid.core.presenters.contracts.VideoLengthView
import org.stepic.droid.di.step.StepScope
import org.stepic.droid.model.Step
import org.stepic.droid.model.Video
import org.stepic.droid.util.TimeUtil
import org.stepic.droid.util.resolvers.VideoResolver
import java.util.concurrent.ThreadPoolExecutor
import javax.inject.Inject

@StepScope
class VideoLengthPresenter
@Inject constructor(
        private val threadPoolExecutor: ThreadPoolExecutor,
        private val mainHandler: MainHandler,
        private val videoResolver: VideoResolver,
        private val videoLengthResolver: VideoLengthResolver) : PresenterBase<VideoLengthView>() {
    var cachedFormat: String? = null

    fun fetchLength(video: Video, step: Step, thumbnailPath: String?) {
        cachedFormat?.let {
            view?.onVideoLengthDetermined(it, thumbnailPath)
            return
        }
        threadPoolExecutor.execute {
            val path = videoResolver.resolveVideoUrl(video, step)
            val millis = videoLengthResolver.determineLengthInMillis(path)
            // if not determine millis -> do not form printable string
            if (millis != null) {
                val printable = TimeUtil.getFormattedVideoTime(millis)
                mainHandler.post {
                    cachedFormat = printable
                    view?.onVideoLengthDetermined(printable, thumbnailPath)
                }
            } else {
                mainHandler.post {
                    view?.onVideoLengthFailed(thumbnailPath)
                }
            }
        }
    }
}
