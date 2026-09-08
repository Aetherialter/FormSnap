package com.formsnap.app.data

import com.formsnap.app.data.local.TaskDao
import com.formsnap.app.data.local.toDomain
import com.formsnap.app.data.local.toEntity
import com.formsnap.app.domain.model.DigitizationTask
import com.formsnap.app.domain.model.TaskStatus
import com.formsnap.app.domain.repository.TaskRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.map

class RoomTaskRepository(
    private val dao: TaskDao,
    private val clock: Clock = Clock.systemUTC(),
    private val nextId: () -> String = { UUID.randomUUID().toString() },
) : TaskRepository {
    override fun observeTasks() = dao.observeTasks().map { tasks -> tasks.map { it.toDomain() } }
    override fun observeTask(id: String) = dao.observeTask(id).map { it?.toDomain() }

    override suspend fun createTask(name: String): DigitizationTask {
        val now = Instant.ofEpochMilli(clock.millis())
        val task = DigitizationTask(
            id = nextId(),
            name = name.trim(),
            status = TaskStatus.DRAFT,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(task.toEntity())
        return task
    }
}
