package org.stepic.droid.core.presenters

import android.support.annotation.MainThread
import android.support.annotation.WorkerThread
import org.stepic.droid.analytic.Analytic
import org.stepic.droid.base.Client
import org.stepic.droid.concurrency.MainHandler
import org.stepic.droid.configuration.Config
import org.stepic.droid.core.internet_state.contract.InternetEnabledListener
import org.stepic.droid.core.presenters.contracts.NotificationListView
import org.stepic.droid.di.notifications.NotificationsScope
import org.stepic.droid.notifications.StepikNotificationManager
import org.stepic.droid.notifications.model.Notification
import org.stepic.droid.notifications.model.NotificationType
import org.stepic.droid.model.NotificationCategory
import org.stepic.droid.util.not
import org.stepic.droid.web.Api
import timber.log.Timber
import java.util.*
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@NotificationsScope
class NotificationListPresenter
@Inject constructor(
        private val threadPoolExecutor: ThreadPoolExecutor,
        private val mainHandler: MainHandler,
        private val api: Api,
        private val config: Config,
        private val analytic: Analytic,
        private val stepikNotificationManager: StepikNotificationManager,
        private val internetEnabledListenerClient : Client<InternetEnabledListener>
) : PresenterBase<NotificationListView>(), InternetEnabledListener {
    private var notificationCategory: NotificationCategory? = null
    val isLoading = AtomicBoolean(false)
    val wasShown = AtomicBoolean(false)
    val hasNextPage = AtomicBoolean(true)
    private val page = AtomicInteger(1)
    val notificationList: MutableList<Notification> = ArrayList<Notification>()
    val notificationMapIdToPosition: MutableMap<Long, Int> = HashMap()

    /**
     * return false if were cancelled
     */
    @MainThread
    fun init(notificationCategory: NotificationCategory): Boolean {
        this.notificationCategory = notificationCategory
        if (!isLoading && !wasShown) {
            //it is not lock, it is just check, but we still can enter twice if we use it in multithreading way, but it is only for main thread.
            isLoading.set(true)
            view?.onLoading()
            if (notificationList.isNotEmpty()) {
                view?.onNeedShowNotifications(notificationList)
                wasShown.set(true)
                isLoading.set(false)
            }

            threadPoolExecutor.execute {
                try {
                    val notifications = getNotificationFromOnePage(notificationCategory)
                    notifications.forEachIndexed { position, notification ->
                        notification.id?.let {
                            notificationId ->
                            notificationMapIdToPosition[notificationId] = position
                        }
                    }
                    mainHandler.post {
                        notificationList.addAll(notifications)
                        wasShown.set(true)
                        view?.onNeedShowNotifications(notificationList) ?: wasShown.set(false)
                    }


                } catch (ex: Exception) {
                    mainHandler.post {
                        view?.onConnectionProblem()
                    }
                } finally {
                    isLoading.set(false)
                }
            }
            return false
        } else {
            //do nothing we loading or already loaded
            return true
        }
    }

    @WorkerThread
    private fun getNotificationFromOnePage(notificationCategory: NotificationCategory): Iterable<Notification> {
        Timber.d("loading from page %d", page.get())
        val notificationResponse = api.getNotifications(notificationCategory, page.get()).execute().body()
        hasNextPage.set(notificationResponse.meta.has_next)
        page.set(notificationResponse.meta.page + 1)

        val baseUrl = config.baseUrl

        var notifications = notificationResponse.notifications
        Timber.d("before filter size is %d", notifications.size)
        notifications = notifications
                .filter {
                    it.htmlText?.isNotBlank() ?: false
                }

        notifications.forEach {
            val notificationHtmlText = it.htmlText ?: ""
            val fixedHtml = notificationHtmlText.replace("href=\"/", "href=\"$baseUrl/")
            it.htmlText = fixedHtml
        }
        Timber.d("after filter size is %d", notifications.size)
        return notifications
    }

    fun loadMore() {
        if (isLoading.get() || !hasNextPage) {
            return
        }

        //if is not loading:
        isLoading.set(true)
        view?.onNeedShowLoadingFooter()
        threadPoolExecutor.execute {
            try {
                notificationCategory?.let { category ->
                    val notifications = getNotificationFromOnePage(category)
                    val oldSize = notificationList.size
                    notifications.forEachIndexed { shift, notification ->
                        notification.id?.let {
                            notificationId ->
                            notificationMapIdToPosition[notificationId] = shift + oldSize
                        }
                    }
                    mainHandler.post {
                        notificationList.addAll(notifications)
                        view?.onNeedShowNotifications(notificationList)
                    }
                }

            } catch (ex: Exception) {
                mainHandler.post {
                    view?.onConnectionProblem()
                }
            } finally {
                isLoading.set(false)
            }
        }
    }

    fun markAsRead(id: Long) {
        threadPoolExecutor.execute {
            try {
                val isSuccess = api.setReadStatusForNotification(id, true).execute().isSuccessful
                if (isSuccess) {
                    mainHandler.post {
                        onNotificationShouldBeRead(id)
                    }
                } else {
                    val pos = notificationMapIdToPosition[id]
                    mainHandler.post {
                        if (pos != null) {
                            view?.notCheckNotification(pos, id)
                        }
                    }
                }

            } catch (ex: Exception) {
                val pos = notificationMapIdToPosition[id]
                mainHandler.post {
                    if (pos != null) {
                        view?.notCheckNotification(pos, id)
                    }
                }
            }
        }
    }

    fun notificationIdIsNull() {
        analytic.reportEvent(Analytic.Notification.ID_WAS_NULL)
    }

    fun onNotificationShouldBeRead(notificationId: Long) {
        val position: Int = notificationMapIdToPosition[notificationId] ?: return
        if (position >= 0 && position < notificationList.size) {
            val notificationInList = notificationList[position]
            if (notificationInList.is_unread ?: false) {
                view?.markNotificationAsRead(position, notificationId)
            }
        }

    }

    @MainThread
    fun markAllAsRead() {
        val notificationCategoryLocal = notificationCategory
        if (notificationCategoryLocal == null) {
            analytic.reportEvent(Analytic.Notification.NOTIFICATION_NULL_POINTER)
        } else {
            analytic.reportEvent(Analytic.Notification.MARK_ALL_AS_READ)
            view?.onLoadingMarkingAsRead()
            threadPoolExecutor.execute {
                try {
                    val response = api.markAsReadAllType(notificationCategoryLocal).execute()
                    if (response.isSuccessful) {
                        notificationList.forEach {
                            it.is_unread = false
                        }
                        mainHandler.post {
                            onMarkCategoryRead(notificationCategoryLocal)
                            view?.markAsReadSuccessfully()
                        }
                    }
                } catch (exception: Exception) {
                    mainHandler.post {
                        view?.onConnectionProblemWhenMarkAllFail()
                    }
                } finally {
                    mainHandler.post {
                        view?.makeEnableMarkAllButton()
                    }
                }
            }
        }

    }

    @MainThread
    fun onMarkCategoryRead(category: NotificationCategory) {
        if (category == notificationCategory) {
            //already mark
            return
        }

        if (notificationCategory == null || (notificationCategory != NotificationCategory.all && category != NotificationCategory.all)) {
            //if we update in not all and it is not all -> do not need extra check
            return
        }

        threadPoolExecutor.execute {
            val listForNotificationForUI = notificationList
                    .filter {
                        it.is_unread ?: false
                    }
                    .filter {
                        if (category == NotificationCategory.all) {
                            true
                        } else {
                            val notCategory: NotificationCategory = when (it.type) {
                                NotificationType.comments -> NotificationCategory.comments
                                NotificationType.other -> NotificationCategory.default
                                NotificationType.review -> NotificationCategory.review
                                NotificationType.teach -> NotificationCategory.teach
                                NotificationType.learn -> NotificationCategory.learn
                                null -> NotificationCategory.all
                            }
                            notCategory == category
                        }
                    }

            val list: List <Pair<Int?, Long?>> = listForNotificationForUI.map {
                val first = notificationMapIdToPosition[it.id]
                Pair(first, it.id)
            }
            if (list.isNotEmpty()) {
                mainHandler.post {
                    list.forEach {
                        if (it.first != null && it.second != null) {
                            view?.markNotificationAsRead(it.first!!, it.second!!)
                        }
                    }
                }
            }
        }
    }

    override fun attachView(view: NotificationListView) {
        super.attachView(view)
        internetEnabledListenerClient.subscribe(this)
    }

    override fun detachView(view: NotificationListView) {
        super.detachView(view)
        internetEnabledListenerClient.unsubscribe(this)
    }


    override fun onInternetEnabled() {
        val category = notificationCategory
        if (notificationList.isEmpty() && category != null) {
            init(category);
        }
    }

    fun tryToOpenNotification(notification: Notification) {
        analytic.reportEvent(Analytic.Notification.NOTIFICATION_CENTER_OPENED)
        stepikNotificationManager.tryOpenNotificationInstantly(notification)
    }

    fun trackClickOnNotification(notification: Notification) {
        analytic.reportEventWithIdName(Analytic.Notification.NOTIFICATION_CLICKED_IN_CENTER, notification.id.toString(), notification.type?.name ?: "")
    }

}