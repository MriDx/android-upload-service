package net.gotev.uploadservice.extensions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import net.gotev.uploadservice.UploadService
import net.gotev.uploadservice.UploadServiceConfig
import net.gotev.uploadservice.UploadTask
import net.gotev.uploadservice.data.UploadTaskParameters
import net.gotev.uploadservice.logger.UploadServiceLogger
import net.gotev.uploadservice.observer.task.UploadTaskObserver

// constants used in the intent which starts this service
private const val taskParametersKey = "taskParameters"
private const val taskClassKey = "taskClass"

private const val actionKey = "action"
private const val uploadIdKey = "uploadId"
private const val cancelUploadAction = "cancelUpload"

fun Context.startNewUpload(taskClass: Class<out UploadTask>, params: UploadTaskParameters): String {
    val intent = Intent(this, UploadService::class.java).apply {
        action = UploadServiceConfig.uploadAction
        putExtra(taskClassKey, taskClass.name)
        putExtra(taskParametersKey, params)
    }

    if (Build.VERSION.SDK_INT >= 26) {
        require(params.notificationConfig != null) {
            "Android Oreo and newer (API 26+) requires a notification configuration for the upload service to run. https://developer.android.com/reference/android/content/Context.html#startForegroundService(android.content.Intent)"
        }
        startForegroundService(intent)
    } else {
        startService(intent)
    }

    return params.id
}

typealias UploadTaskCreationParameters = Pair<Class<out UploadTask>, UploadTaskParameters>

@Suppress("UNCHECKED_CAST")
fun Intent?.getUploadTaskCreationParameters(): UploadTaskCreationParameters? {
    if (this == null || action != UploadServiceConfig.uploadAction) {
        UploadServiceLogger.error(UploadService.TAG) { "Error while instantiating new task. Invalid intent received" }
        return null
    }

    val taskClassString = getStringExtra(taskClassKey) ?: run {
        UploadServiceLogger.error(UploadService.TAG) { "Error while instantiating new task. No task class defined in Intent." }
        return null
    }

    val taskClass = try {
        Class.forName(taskClassString)
    } catch (exc: Throwable) {
        UploadServiceLogger.error(UploadService.TAG, exc) { "Error while instantiating new task. $taskClassString does not exist." }
        null
    } ?: return null

    if (!UploadTask::class.java.isAssignableFrom(taskClass)) {
        UploadServiceLogger.error(UploadService.TAG) { "Error while instantiating new task. $taskClassString does not extend UploadTask." }
        return null
    }

    val params: UploadTaskParameters = getParcelableExtra(taskParametersKey) ?: run {
        UploadServiceLogger.error(UploadService.TAG) { "Error while instantiating new task. Missing task parameters." }
        return null
    }

    return UploadTaskCreationParameters(taskClass as Class<out UploadTask>, params)
}

/**
 * Creates a new task instance based on the requested task class in the intent.
 * @param intent intent passed to the service
 * @return task instance or null if the task class is not supported or invalid
 */
fun Context.getUploadTask(
    creationParameters: UploadTaskCreationParameters,
    notificationId: Int,
    vararg observers: UploadTaskObserver
): UploadTask? {
    return try {
        val uploadTask = creationParameters.first.newInstance().apply {
            init(
                context = this@getUploadTask,
                taskParams = creationParameters.second,
                notificationId = notificationId,
                taskObservers = *observers
            )
        }

        UploadServiceLogger.debug(UploadService.TAG) { "Successfully created new task with class: ${creationParameters.first.name}" }
        uploadTask
    } catch (exc: Throwable) {
        UploadServiceLogger.error(UploadService.TAG, exc) { "Error while instantiating new task" }
        null
    }
}

fun Context.getNotificationActionIntent(
    uploadId: String,
    requestCode: Int,
    action: String
): PendingIntent {
    val intent = Intent(UploadServiceConfig.broadcastNotificationAction).apply {
        `package` = UploadServiceConfig.namespace
        putExtra(actionKey, action)
        putExtra(uploadIdKey, uploadId)
    }

    return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT)
}

fun Context.getCancelUploadIntent(uploadId: String, requestCode: Int = 1) =
    getNotificationActionIntent(uploadId, requestCode, cancelUploadAction)

val Intent.uploadIdToCancel: String?
    get() {
        if (getStringExtra(actionKey) != cancelUploadAction) return null
        return getStringExtra(uploadIdKey)
    }
