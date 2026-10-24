package dev.ebookd.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one exchange with the server: pull what other devices have read, push what this one has.
 *
 * A failure is retried with backoff rather than dropped, because the usual cause is a network that
 * is not there yet — the reader is on a train, and the position they are accumulating is exactly
 * what the outbox exists to hold. After [MAX_ATTEMPTS] the work gives up and leaves the rows in
 * place: nothing is lost, the account screen shows why, and the next page turn or app launch starts
 * a fresh attempt.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = sync.syncNow().fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() },
    )

    companion object {
        const val WORK_NAME = "progress-sync"
        private const val MAX_ATTEMPTS = 6
    }
}
