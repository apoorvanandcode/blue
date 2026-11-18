package app.blue.sms.domain.upcoming

import app.blue.sms.data.db.UpcomingDao
import app.blue.sms.domain.model.UpcomingStatus
import javax.inject.Inject

/** Nothing enters Upcoming on its own: proposals wait for accept. Closing and dismissing are by hand too. */
class UpcomingActions @Inject constructor(private val dao: UpcomingDao) {
    suspend fun accept(id: Long) = set(id) { it.copy(status = UpcomingStatus.ACTIVE, acceptedAt = System.currentTimeMillis()) }
    suspend fun reject(id: Long) = set(id) { it.copy(status = UpcomingStatus.REJECTED) }
    suspend fun close(id: Long) = set(id) { it.copy(status = UpcomingStatus.CLOSED, closedAt = System.currentTimeMillis()) }
    suspend fun dismiss(id: Long) = set(id) { it.copy(status = UpcomingStatus.REJECTED, closedAt = System.currentTimeMillis()) }
    suspend fun rejectAllProposals() = dao.moveAll("PROPOSED", "REJECTED")

    private suspend fun set(id: Long, change: (app.blue.sms.data.db.UpcomingItemEntity) -> app.blue.sms.data.db.UpcomingItemEntity) {
        dao.get(id)?.let { dao.update(change(it)) }
    }
}
