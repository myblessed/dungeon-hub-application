package net.dungeonhub.application.misc

import net.dungeonhub.model.carry_queue.CarryQueueModel

data class LoggedQueueEntry(
    val queues: List<CarryQueueModel>,
    val updatedScore: Long
)