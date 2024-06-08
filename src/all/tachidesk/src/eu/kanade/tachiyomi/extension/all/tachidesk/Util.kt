package eu.kanade.tachiyomi.extension.all.tachidesk

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import rx.Emitter
import rx.Observable
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
fun <T : Any> Flow<T>.asObservable(
    backpressureMode: Emitter.BackpressureMode = Emitter.BackpressureMode.NONE,
): Observable<T> {
    return Observable.create(
        { emitter ->
            /*
             * ATOMIC is used here to provide stable behaviour of subscribe+dispose pair even if
             * asObservable is already invoked from unconfined
             */
            val job = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.ATOMIC) {
                try {
                    collect { emitter.onNext(it) }
                    emitter.onCompleted()
                } catch (e: Throwable) {
                    // Ignore `CancellationException` as error, since it indicates "normal cancellation"
                    if (e !is CancellationException) {
                        emitter.onError(e)
                    } else {
                        emitter.onCompleted()
                    }
                }
            }
            emitter.setCancellation { job.cancel() }
        },
        backpressureMode,
    )
}
