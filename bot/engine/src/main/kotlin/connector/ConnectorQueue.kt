package ai.tock.bot.connector

import ai.tock.bot.engine.action.Action
import ai.tock.shared.Executor
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ConnectorQueue(private val executor: Executor) {

    private data class ActionWithTimestamp(val action: Action, val timestamp: Long)

    private val messagesByRecipientMap: Cache<String, ConcurrentLinkedQueue<ActionWithTimestamp>> =
        CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.MINUTES)
            .build()

    /**
     * Add an action to send to a queue by recipient.
     *
     * @param action the action to send
     * @param delayInMs the optional delay
     * @params
     */
    fun add(action: Action, delayInMs: Long, send: (action: Action) -> Unit) {
        val actionWrapper = ActionWithTimestamp(action, System.currentTimeMillis() + delayInMs)

        val queue = messagesByRecipientMap
            .get(action.recipientId.id) { ConcurrentLinkedQueue() }
            .apply {
                synchronized(this) {
                    peek().also { existingAction ->
                        offer(actionWrapper)
                        if (existingAction != null) {
                            return
                        }
                    }
                }
            }
        executor.executeBlocking(Duration.ofMillis(delayInMs)) {
            sendActionFromConnector(actionWrapper, queue, send)
        }
    }

    private fun sendActionFromConnector(
        action: ActionWithTimestamp,
        queue: ConcurrentLinkedQueue<ActionWithTimestamp>,
        send: (action: Action) -> Unit
    ) {
        try {
            val timeToWait = action.timestamp - System.currentTimeMillis()
            if (timeToWait > 0) {
                Thread.sleep(timeToWait)
            }
            send(action.action)
        } finally {
            synchronized(queue) {
                //remove the current one
                queue.poll()
                queue.peek()
            }?.also { a ->
                sendActionFromConnector(a, queue, send)
            }
        }
    }
}